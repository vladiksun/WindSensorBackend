# Design

## Context

See `proposal.md` — Why for motivation, and the spec delta for the required behaviour. The facts that shape the approach:

- `WindSensorController.getSensorData` delegates to `ProxyService.requestTimedReadings(Option<Integer>, Option<Integer>, SensorDTO)`, which resolves the provider by name from its own `Map<String, WindDataProvider<?>>`, builds the URL through `provider.getCallUrl(sensorId)`, layers the provider's `requestHeaders(sensorId)` over `Accept: application/json`, executes one bounded Apache HttpClient5 call and hands the body to `provider.extractTimedReadings(...)`. Providers parse; they never fetch.
- The effective `readingWindow` / `numberOfReadings` are resolved today **after** the response arrives, inside `ProxyService.handleResponse`, from request values → `READING_WINDOW_SECONDS_DEFAULT` (3600) / `NUMBER_OF_READINGS_DEFAULT` (5) → overridden by `SensorDTO.readingWindow()` / `numberOfReadings()`.
- Ehcache is already a production dependency (`micronaut-cache-core` + `micronaut-cache-ehcache`) with two named caches configured under `ehcache.caches` in `application.yml` — `osm-tiles` and `osm-composites`, both heap+disk, sharing `ehcache.storage-path` (`EHCACHE_STORAGE_PATH`, already volume-mapped in `dev_setup/docker-compose.yml`).
- Both tile caches store an application-managed expiry inside the value (`CachedTile.expiresAtEpochMillis`, same for `CachedComposite`) because Ehcache 3 TTL is a cache-level setting and micronaut-cache's Ehcache configuration exposes tiers (`heap.max-entries`, `disk.max-size`), not per-entry TTL.
- Requests run on virtual threads (`@ExecuteOn(TaskExecutors.VIRTUAL)`); the upstream client issues exactly one call per client request (automatic retries disabled).

## Goals / Non-Goals

**Goals:**

- Put one cache in front of the provider fetch so that no `WindDataProvider` implementation knows caching exists.
- Give every entry its own expiry, drawn from a configurable band around a configurable base TTL.
- Keep `/sensor-data` byte-compatible for clients and keep existing failure semantics untouched.
- Make the cache observable (status per response) and switchable off without a code change.

**Non-goals:**

- No rate limiting, cooldown or `Retry-After` handling; no provider-level governor (`docs/windguru_api_rate_limit.md` tracks that separately).
- No runtime purge/invalidation API for this cache and no `skipCache` parameter on `/sensor-data`.
- No disk persistence, no per-provider TTLs, no shared abstraction over the tile caches.

## Decisions

### 1. Expiry travels in the cached value, not in Ehcache configuration

Each entry is a small immutable record — readings plus `expiresAtEpochMillis` and `storedAtEpochMillis` — whose expiry is computed when the entry is written. A lookup reads the entry, compares its expiry against now, treats an expired entry as absent (and removes it), and otherwise serves the stored readings. Time-to-live semantics: repeated reads never extend an entry's life, which is what "floating TTL" requires; a time-to-idle cache would let a hot poll pin an entry forever.

Why not Ehcache's own expiry: `Expirations.timeToLive(Duration)` is fixed per cache instance and micronaut-cache exposes no per-entry TTL knob, so a random offset cannot be expressed in `application.yml` at all. A custom `org.ehcache.expiry.Expiry` attached to this cache could compute a random duration per write, but it moves the jitter logic into a cache-engine extension point that unit tests cannot reach directly, hides the actual expiry instant from every consumer (the header and the tests need it), and still cannot express the enable/disable switch. The alternative also contradicts the pattern already proven in this repository for `CachedTile` and `CachedComposite`.

### 2. The cache lives in the shared fetch path (`ProxyService`), not in providers

`ProxyService.requestTimedReadings` becomes: validate → resolve provider → **resolve effective window/count** → build cache key → valid entry? return it as `HIT` : single-flight fetch → parse → store with computed expiry → return as `MISS`. Providers are untouched, so `windy`, `neduet`, `onechip`, `windguru` and any provider added later are cached by identical code — the transparency requirement in the spec.

Alternatives considered:

- **`@Cacheable` on the service method.** Rejected: Micronaut's cache interceptor applies one configuration per cache name, so it cannot produce a random TTL per entry; its key derivation over `Option<Integer>` plus a `SensorDTO` body needs a dedicated key type anyway; and "never cache a failure" would have to be argued against an interceptor that caches whatever the method returns.
- **A decorator around each `WindDataProvider`.** Rejected: providers only parse, so a provider-side decorator would either cache parse results while still paying for the HTTP fetch, or push HTTP ownership into providers and duplicate the shared bounded-client logic four times.

### 3. Cache the normalized readings, keyed by the reduction parameters

The stored value is the already windowed and reduced `List<SensorDataDTO>` — a handful of records per sensor — and the key carries the effective `readingWindow` and `numberOfReadings`, because those change the response body. Key shape: `sensor-data/<provider>/<sensorId>/<window>/<count>`.

### 4. Effective window and count are resolved before the fetch

`ProxyService` gains one small step that resolves `readingWindow` and `numberOfReadings` once — request value, then the provider-independent defaults, then the sensor's own `readingWindow()` / `numberOfReadings()` override — and passes the resolved pair both to the cache key and to the provider call. Today the same resolution happens in `handleResponse`; moving it earlier is behavior-preserving because none of those three inputs depend on the response body, and the defaults stay the existing `READING_WINDOW_SECONDS_DEFAULT` / `NUMBER_OF_READINGS_DEFAULT`. Keeping the resolution in one place also removes the duplicate resolution that `ResponseHandlerContext` currently carries.

### 5. One new named Ehcache cache, heap tier only

`application.yml` gains `ehcache.caches.sensor-data-cache.heap.max-entries` (a few hundred is generous: distinct keys are providers × sensors × window/count variants, and production lists a handful of sensors) next to the existing `osm-tiles` and `osm-composites` declarations. No `disk` tier is configured, so nothing new lands under `ehcache.storage-path` and `dev_setup/docker-compose.yml` stays as it is.

Rationale for skipping the disk tier: an entry lives for minutes, so persisting it only risks serving a reading that survived a restart — which the spec forbids — while adding file I/O for values smaller than a kilobyte. Consequences worth recording: (a) because Ehcache enforces no TTL here, expired entries disappear when a lookup finds and removes them, or when the bounded heap evicts them; (b) heap-only does **not** exempt the value from Java serialization: Micronaut's Ehcache integration validates every value against `Serializable` when it writes it, and a value that fails that check makes the write throw. So `CachedSensorReadings` implements `Serializable`, exactly as `CachedTile` had to, and its payload record `SensorDataDTO` too — discovered during implementation, not from the configuration.

Ehcache named caches reach the application through Micronaut's cache abstraction, injected the way `OsmTileService` injects its own: `@Singleton … (@Named("sensor-data-cache") SyncCache<Cache> cache)`. The existing AOT settings already run Ehcache in production for tiles, so no build or AOT change is needed.

### 6. Jitter arithmetic and configuration surface

```
baseMillis   = wind-sensor.sensor-data-cache.ttl              (Duration, default 5m)
windowMillis = wind-sensor.sensor-data-cache.floating-window   (Duration, default 1m)
jitter       = ThreadLocalRandom.current().nextLong(windowMillis + 1)   // 0 … window inclusive
signedJitter = nextBoolean() ? jitter : -jitter                          // ± as the request specifies
effectiveTtl = max(baseMillis + signedJitter, 1_000)                     // never zero or negative
expiresAt    = now + effectiveTtl
```

`Duration` binding follows the precedent of `osm-tiles.min-ttl: 7d`, so `CACHE_TTL=90s` and `CACHE_TTL_FLOATING_WINDOW=0` are ordinary values; a window larger than the TTL is legal because of the one-second floor. `ThreadLocalRandom` needs no reflection, which keeps the project's reflection-free rule intact.

Testability: the expiry computation lives in its own tiny component (a TTL/expiry calculator over `(base, window)`) so unit tests can assert band membership over many draws, exact expiry when the window is zero, and the floor case where window ≥ TTL — without starting a server or reaching into randomness. The draw happens once per write only.

### 7. Single-flight coalescing per cache key

A `ConcurrentHashMap<String, CompletableFuture<Entry>>` guards misses: `putIfAbsent(key, newFuture)` picks one winner, that request performs the bounded upstream call, completes the future with either the stored entry or the failure, and removes **its own** future from the map in a `finally` (removing only if the mapped value is still the one it installed, so a later attempt is never clobbered). Followers wait on that future and return the same outcome, marking their response `MISS` because nothing was cached when they arrived. A coalesced failure propagates to every waiter, so each fails as an upstream error exactly as today.

Implementation constraints: never block inside `computeIfAbsent` — a blocking HTTP call there would hold a map bin lock against unrelated keys; waiters bound their own wait (a margin over the configured upstream connect/response timeouts) so a wedged leader cannot pin virtual threads indefinitely; different keys never wait on each other, which the spec requires.

### 8. The fetch outcome carries a cache status; failures propagate untouched

`ProxyService.requestTimedReadings` returns `Try<SensorDataOutcome>` instead of `Try<List<SensorDataDTO>>`, where the outcome is a record of the readings plus `CacheStatus` (`HIT`, `MISS`). The controller becomes `MutableHttpResponse<List<SensorDataDTO>>`, sets `X-Cache` from that status, and keeps its existing failure mapping — `IllegalArgumentException` / `NullPointerException` to 400, anything else to 502 with the same message. The cache layer never wraps or replaces a throwable: a provider's `HttpClientError`, `ParseResponseException` or "provider not found" reaches the controller as the identical instance it has today, so error statuses and JSON error bodies are unchanged.

Alternatives considered: a response filter that reads a request attribute (indirect, and invisible to unit tests of the service) and shipping no status at all (rejected — without `X-Cache`, an operator cannot tell "the provider is broken" from "we are serving a cached reading", which is exactly the diagnosis this change must make easy).

### 9. Test strategy keeps existing suites untouched

`application-test.yml` sets `wind-sensor.sensor-data-cache.enabled: false`, so all nineteen current MockServer scenarios — each of which asserts **exactly one** upstream call, including `WindSensorControllerUpstreamTimeoutTest` — keep passing unchanged. Cache behaviour is then covered by a dedicated suite that re-enables caching through test properties with a short TTL and a zero floating window for determinism, asserting upstream call counts recorded by a deterministic loopback stub (`StubUpstreamServer`, one handler per path, counted calls, handlers on their own threads so a deliberately stalled request cannot block the others) plus the `X-Cache` header: hit, expiry-driven miss, no caching on `503` or transport failure, caching of a successful-but-empty result, key separation across provider/window/count, one fetch for a concurrent burst of identical requests, and independent fetches for two different sensors. MockServer is deliberately not used for these cases — version 5.15 cannot delay a response, which the transport-timeout case needs, and counting calls at a stub is simpler than querying an invocation log. Randomness itself is covered by unit tests on the expiry calculator (band membership over many draws, exact expiry at window zero, floor when window ≥ TTL) rather than by timing-sensitive integration tests.

## Risks / Trade-offs

- **[Clients receive an unchanged reading for up to six minutes]** → Accepted and visible: `X-Cache: HIT` names the cause, the TTL is one environment variable, and these providers publish new rows roughly every 1–3 minutes anyway, so a 5-minute band costs little freshness.
- **[An offline sensor keeps serving its zero-valued reading for the whole window]** → Accepted deliberately (that is the abuse protection), with recovery latency tunable per deployment; whether empty results deserve a shorter TTL than real readings is an open question below.
- **[A provider outage becomes less visible because cached answers keep looking healthy]** → Mitigated by logging on every miss and by the header; failures are never served from cache, so an outage reappears as soon as entries expire.
- **[Existing tests break if caching leaks into the test profile]** → Prevented by `enabled: false` in `application-test.yml` and by keeping cache assertions in a separate suite.
- **[Expired entries occupy heap until a lookup or eviction removes them]** → Bounded by `heap.max-entries` over a small key space, and lookups remove expired entries eagerly; entry size is a few hundred bytes.
- **[Coalescing couples waiters to one call's outcome]** → Waiters inherit the leader's failure (identical to today's per-request failure) and their wait is bounded by the already-configured upstream timeouts.
- **[Random expiry makes behaviour harder to reason about]** → Mitigated by a dedicated calculator with unit tests, `window=0` for deterministic test runs, and exact bounds stated in the spec (`ttl ± window`, floor 1 s).

## Migration Plan

1. Land the code and configuration together; the new cache is enabled by default with the 5-minute base TTL, so deploying starts caching immediately — no client or frontend change.
2. Verify in production by polling one sensor twice within a minute and confirming `X-Cache: MISS` then `X-Cache: HIT`, and by watching upstream call volume drop for the affected sensors.
3. Tune per deployment with `CACHE_TTL` / `CACHE_TTL_FLOATING_WINDOW` if a spot needs fresher data.
4. Roll back without a rebuild by setting `wind-sensor.sensor-data-cache.enabled: false` (env override for the flag), which restores today's one-call-per-request behaviour; the tile caches are unaffected in either direction.

## Open Questions

- Should a successful-but-empty result get a shorter TTL than a real reading, so a recovered sensor comes back sooner? Deferred until there is evidence that recovery latency matters more than the extra calls.
- Should `/sensor-data` gain a `skipCache` parameter and a purge endpoint (which would need the `micronaut-cache-management` dependency this project does not currently have)? Deferred — no consumer needs it yet, and the enable flag plus TTL already cover the operational cases.
- Should the future Windguru request governor (`docs/windguru_api_rate_limit.md`, which proposes a 300-second response cache) reuse this cache rather than introduce its own? The 5-minute default was chosen to line up with that proposal; decide when that change is specified.


