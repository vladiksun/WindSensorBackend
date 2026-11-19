# Design

## Context

See `proposal.md` — Why for motivation. The current implementation is:

- `WindguruDataProvider` extends `BaseWindyDataProvider`, keeps the public provider name `windguru`, builds the configured station-data URL, and declares only `Referer`.
- `ProxyService` owns the main upstream Apache HttpClient5 call, sets `Accept: application/json`, then lets a provider's `requestHeaders(sensorId)` override default headers.
- `/sensor-data` already has a heap-only floating-TTL response cache and per-key single-flight coalescing. Failures are not cached.
- The 2026-11-19 egress evidence shows that the current station-data URL now requires both `Referer` and `X-WG-Token`. A cookie-less plain HTTP request to `https://www.windguru.cz/int/iapi.php?q=token_refresh` can mint a token with advertised `ttl: 1800`.
- The same day's production outage showed the token action itself is one of those referring requests: from the production egress, `q=token_refresh` answered `401 {"return":"error","message":"Unauthorized!"}` for `Accept: application/json` with no `Referer`, whatever `User-Agent` was sent, and `200` with a token as soon as any page of windguru.cz was named — including the site root. The station page `15693` itself answered `200` with live data, so nothing about this host is blocked and no browser (Playwright, HtmlUnit) is required.

This change restores the existing unofficial Windguru provider from the production egress IP while preserving the public `/sensor-data` contract. It does not introduce the official authenticated Windguru API v1 or a general rate-limit governor.

## Goals / Non-Goals

**Goals:**

- Rename only the implementation class to `WindguruSimpleHttpClientDataProvider`, leaving provider selection and configuration unchanged.
- Mint Windguru tokens with a small provider-owned HTTP client when needed.
- Cache a token in process memory, refresh it before its advertised expiry, and use it on every station-data request.
- Keep payload parsing, unit conversion, windowing, caching, single-flight behavior, and successful response bodies unchanged.
- Fail fast when no valid token is available instead of issuing a station-data call that the live evidence proves will be rejected with `403`.

**Non-Goals:**

- No official Windguru API v1 adapter, Bearer/API-key configuration, quota-header handling, or separate provider bean for the official API.
- No general upstream governor, long cooldown, `Retry-After` honoring for station data, retries, or distributed token cache.
- No cookies, browser automation, persistent session state, disk persistence, or database state.
- No change to client request bodies, successful response bodies, cache keys, `X-Cache` values, or existing provider names.

## Decisions

### 1. Keep the capability under `windguru-wind-provider`

The externally observable behavior is still Windguru provider behavior: token headers are a requirement of the same `/sensor-data` provider path. Introducing a new capability would split authentication from the provider it belongs to and duplicate URL/payload requirements already owned by `openspec/specs/windguru-wind-provider/spec.md`.

**Alternative considered:** A separate `windguru-authentication` capability. Rejected because token acquisition only exists to make Windguru station-data requests succeed; it is not a standalone user-facing behavior.

### 2. Rename the class but keep the provider name and config key

The implementation file and class become:

- `src/main/java/com/vb/wingfoil/provider/impl/WindguruSimpleHttpClientDataProvider.java`
- `com.vb.wingfoil.provider.impl.WindguruSimpleHttpClientDataProvider`

The static provider name stays `NAME = "windguru"`, the bean continues to be registered through `@Singleton`, and configuration remains:

```yaml
wind-sensor:
  wind-providers:
    windguru:
      url: https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1&graph_info=1
```

The test class should be renamed alongside it to `WindguruSimpleHttpClientDataProviderTest`.

**Alternative considered:** Rename the public provider key. Rejected because external spot configs and clients use `provider: windguru`, and the user's requested rename is an implementation-name change, not a breaking API change.

### 3. Mint tokens from the same configured Windguru script path

The provider derives a token URL once at construction time from the configured station-data URL template. It keeps only the script/path part and discards all station-data query parameters. If the placeholder `%s` is present in the path, it is removed; if it is only in the discarded query string, nothing else is needed.

Algorithm:

1. Split the configured URL at the first `?`.
2. Take the left side as the base script/path.
3. Replace a literal `%s` placeholder in that base with an empty string.
4. Append exactly `?q=token_refresh`.

Examples:

```text
https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s...
  -> https://www.windguru.cz/int/iapi.php?q=token_refresh

http://127.0.0.1:PORT/api/windguru/%s?q=station_data
  -> http://127.0.0.1:PORT/api/windguru/?q=token_refresh
```

This keeps production config unchanged and makes MockServer or loopback tests able to stub the token action under the same configured provider URL family.

**Alternative considered:** Add `wind-sensor.wind-providers.windguru.token-url`. Deferred: no evidence yet that a different token endpoint is required, and adding a new shared-provider config field now would widen the configuration surface before it is needed. If Windguru later moves token minting, a small follow-up change can add an explicit override.

### 4. Give the provider its own small HTTP client for token minting only

The main station-data call remains owned by `ProxyService`. The provider owns a narrow Apache HttpClient5 instance used only for `q=token_refresh`, with its own pool, the same configured upstream timeout bounds (`wind-sensor.upstream-*`), no cookie store or session state, and no automatic retries. This is why the class name says "SimpleHttpClient": the extra behavior is a simple auxiliary HTTP call for token minting, not a replacement of the shared station-data request path.

**Alternative considered:** Reuse `ProxyService`'s HttpClient by injecting or exposing it as a bean. Rejected because providers currently do not depend on `ProxyService`, and doing so would couple a plugin to the service that owns them.

**Alternative considered:** Make every Windguru data request through this provider-owned HTTP client. Rejected because `ProxyService` already owns caching, single-flight, upstream status mapping, response parsing handoff, and timeout bounds for `/sensor-data`; duplicating those in one provider would create divergent behavior.

### 5. Use a token snapshot plus a short safety margin

Add a private immutable snapshot concept:

```java
record TokenSnapshot(String token, Instant expiresAt) {}
```

The provider keeps the current snapshot in an `AtomicReference` and refreshes under a `ReentrantLock` with double-checking:

1. Read the cached snapshot outside the lock.
2. Return it if `expiresAt` is after `clock.instant()`.
3. Lock, re-read, fetch one token only if still absent or expired, store the new snapshot, then return it.

The effective lifetime is computed from the upstream TTL with a safety margin and an upper cap:

- default when missing or non-positive: 900 seconds;
- subtract 60 seconds from the advertised TTL before capping;
- cap the safe TTL at 900 seconds;
- floor at 1 second so a malformed tiny TTL cannot create an already-expired snapshot.

For the observed `ttl: 1800`, the provider uses at most 900 seconds, satisfying the "cache it for < 1800 s" finding while reducing the stale-token window if Windguru revokes tokens earlier than advertised.

**Alternative considered:** Use a full single-flight future for token refresh. Rejected as overkill: refreshes are rare because the provider holds one process-wide token and `/sensor-data` response caching already reduces station polling. A lock with double-checking is simpler and correct enough for this bounded auxiliary call.

### 6. Parse only the token fields needed by the backend

The observed `token_refresh` payload is:

```json
{
  "return": "OK",
  "message": "OK",
  "data": {
    "token": "...",
    "ttl": 1800,
    "wgk": {
      "cur": "...",
      "next": "...",
      "switch_in": 103,
      "period": 3600
    }
  }
}
```

The provider only needs `data.token` and optionally `data.ttl`. Add narrow wire models under `com.vb.wingfoil.response.windguru`:

```java
@Serdeable record WindguruTokenResponse(WindguruTokenData data) {}

@Serdeable record WindguruTokenData(String token, Integer ttl) {}
```

This intentionally does not map the reserved top-level `return` key or the diagnostic `message` field. The provider accepts a refresh response only when it is HTTP 2xx, parses as JSON, has `data`, and contains a non-blank `token`. A missing `data`, blank token, invalid JSON, or transport failure is treated as token acquisition failure.

**Alternative considered:** Map `return` / `message` strictly. Deferred because the backend only needs a usable token; requiring those diagnostic fields would add reserved-word mapping complexity without improving the contract unless Windguru starts returning tokens in error-shaped payloads.

### 7. Provider headers return both required values or fail before station data

`requestHeaders(sensorId)` becomes:

```text
Referer: https://www.windguru.cz/station/<sensorId>
X-WG-Token: <currently valid minted token>
```

If no valid cached token exists and token acquisition fails, it throws an `AppException` with status `502`, a short client-safe message such as "Windguru authentication unavailable", and the original failure as its cause. The provider does not return a Referer-only map in that case because the live matrix shows Referer without token answers `403`.

**Alternative considered:** Send station data without the token when minting fails. Rejected because it wastes an upstream call on a request shape already known to be forbidden and makes logs less clear about whether station-data or token acquisition failed.

### 8. Wrap provider header preparation in the existing Try path

`ProxyService.fetchReadings` currently builds the HTTP request and applies provider headers before its `Try.of(...)` around execution. To make a provider-thrown `AppException` behave consistently in both cache-disabled and cache-enabled paths, move request construction, header application, and execution into one `Try.of(...)` supplier.

This keeps existing behavior for providers that do not throw: same URL building, same header override semantics, same response handling, same failure mapping. It also ensures a token-minting failure is logged through the existing `onFailure` path and mapped by `GlobalExceptionHandler`.

**Alternative considered:** Change `WindDataProvider.requestHeaders` to declare checked exceptions. Rejected because it widens every provider's contract for one provider-specific need; runtime application failures already have an established mapping path.

### 9. Do not add token failure caching or long backoff

Failures must not be cached, consistent with the existing `/sensor-data` cache. Token acquisition failures also fail fast and are not retried inside the provider. A future governor change can add short cooldowns or `Retry-After` handling if production shows sustained upstream abuse pressure.

**Alternative considered:** Add a 30-second token failure cooldown now. Deferred because it changes operational behavior beyond the proposed fix and would need its own spec requirements. If repeated token failures appear after deployment, open a separate rate-limit/governor change.

### 10. Exact refresh sequence

Token refresh is lazy, provider-internal, and triggered only when a Windguru station-data call is about to happen. `/sensor-data` response-cache hits do not call the provider at all, so they do not mint tokens.

Implementation shape:

```java
Map<String, String> requestHeaders(String sensorId) {
    var token = currentValidToken();
    return Map.of(
            "Referer", REFERER_PREFIX + sensorId,
            "X-WG-Token", token);
}

private String currentValidToken() {
    var now = clock.instant();
    var cached = cachedToken.get();
    if (isValid(cached, now)) {
        return cached.token();
    }

    lock.lock();
    try {
        cached = cachedToken.get();
        if (isValid(cached, now)) {
            return cached.token();
        }

        var fresh = fetchFreshToken().getOrElseThrow(cause ->
                new AppException(502, "Windguru authentication unavailable", cause));
        cachedToken.set(fresh);
        return fresh.token();
    } finally {
        lock.unlock();
    }
}

private Try<TokenSnapshot> fetchFreshToken() {
    var request = new HttpGet(tokenUrl);
    request.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);
    // Live 2026-11-19: q=token_refresh answers 401 without a referring page, whatever the User-Agent is.
    request.setHeader(HttpHeaders.REFERER, tokenReferer());

    return Try.of(() -> tokenHttpClient.execute(request, response -> readAndValidateToken(response)))
            .map(this::toSnapshot);
}

// The site root of the configured endpoint: https://www.windguru.cz/ in production, the stub's own root in tests.
private String tokenReferer() {
    var uri = URI.create(tokenUrl);
    var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";

    return uri.getScheme() + "://" + uri.getHost() + port + "/";
}
```

`readAndValidateToken` checks HTTP 2xx, parses only `data.token` and optional `data.ttl`, and rejects missing or blank tokens. No station-data request is built or executed if `requestHeaders` throws.

## Risks / Trade-offs

- [The `token_refresh` endpoint is unofficial and can change shape or become cookie/session-bound] → Keep parsing narrow, ignore unknown fields, log token acquisition failures without logging the token value, and fail through the existing sanitized error path. If Windguru changes again, an official API adapter remains a separate planned route.
- [A cached token may be revoked earlier than its advertised TTL] → Use a 60-second safety margin and cap effective lifetime at 900 seconds; do not rely on the full upstream 1800-second TTL.
- [Each backend instance mints its own token] → Accepted for the current single-instance deployment. If horizontal scaling grows, monitor token-refresh volume before adding a distributed token cache or official API quota handling.
- [Provider cannot invalidate a cached token after station-data returns `403`] → The shared request path does not currently report non-2xx statuses back to providers. Mitigation is short capped TTL and clear failure logs; a future provider-status hook would be a separate change.
- [No long cooldown could still hammer a failing token endpoint] → Existing `/sensor-data` response caching reduces successful polling, but failures are intentionally not cached. If production shows abusive retry pressure, add governor/cooldown behavior in a dedicated OpenSpec change rather than mixing retries into this fix.

## Migration Plan

1. Deploy the renamed provider with unchanged `wind-sensor.wind-providers.windguru.url`.
2. Verify locally that token minting and station-data tests use local fixtures or loopback stubs, not live Windguru endpoints.
3. After deployment, check server logs for successful Windguru `/sensor-data` responses and the absence of `x-wg-token-state: missing`.
4. Roll back by reverting the code rename/token-header change; no external configuration migration is required because provider name and config key remain unchanged.

## Open Questions

- Should a future official Windguru API v1 provider replace this simple HTTP client provider, or should it coexist as a separate provider selected per sensor?
- Should `token-url` become an explicit configuration key if Windguru moves token minting away from the same `iapi.php` path?
- Should a later governor change add short failure cooldowns and honor upstream `Retry-After` for both token and station-data calls?


