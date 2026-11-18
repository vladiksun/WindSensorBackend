# Proposal

## Why

Every `POST /sensor-data` request issues a fresh upstream call: the watch polls each sensor roughly every 12 seconds, and each poll re-downloads a full provider payload — a ~34 KB `1chip.ru` HTML page or a Windguru station-history JSON. Production already shows what that costs. Windguru answers `403 {"return":"error","message":"forbidden"}` with `Retry-After: 343781` (≈4 days), which `docs/windguru_api_rate_limit.md` reads as an IP-level anti-abuse cooldown against polling that is perfectly periodic and byte-identical every time. Caching each response for about five minutes removes roughly 25 upstream calls per sensor per hour, keeps the client-visible contract identical, and makes this backend's outbound cadence irregular instead of a metronomic heartbeat.

## What Changes

- **Cache-first `POST /sensor-data`.** A valid cached entry answers the request with no upstream call at all; a missing or expired entry fetches from the provider, parses it, stores the result and returns it.
- **A new, separate EhCache cache** named `sensor-data-cache`, declared alongside — and independent of — the existing `osm-tiles` and `osm-composites` caches. Heap-bounded with no disk tier: cached readings live for minutes, so a restart must not resurrect stale wind data.
- **Configurable base TTL, default 5 minutes** — `wind-sensor.sensor-data-cache.ttl`, overridable by the `CACHE_TTL` environment variable.
- **Floating (dynamic) TTL per entry.** At store time the effective lifetime is `ttl ± jitter`, where the jitter magnitude is drawn uniformly from `[0, CACHE_TTL_FLOATING_WINDOW]` and applied with a random sign. Default window is **1 minute**, so the default expiry lands somewhere in `[4 min, 6 min]`. The purpose of the window is anti-detection: entries expire at irregular instants, so upstream refetch times are not a fixed interval an upstream can fingerprint.
- **Cache key identifies provider and effective request parameters** — provider name + sensor `id` + resolved `readingWindow` + resolved `numberOfReadings`. Providers never share entries, and different windows or counts never collide. This requires resolving the effective window/count *before* the upstream call; today that resolution happens inside response handling.
- **Transparent for every `WindDataProvider`.** Caching lives in the shared request path (`ProxyService`), not in any provider. Every registered provider — `windy`, `neduet`, `onechip`, `windguru` and any provider added later — is cached by the same code with no provider-side change, and the provider-declared `requestHeaders` behaviour is untouched.
- **Failures are never cached.** A non-2xx or unreachable upstream still fails exactly as it does today and stores nothing. A successful (`2xx`) response whose provider produced no readings **is** cached, so an offline sensor stops being re-polled every 12 seconds.
- **Concurrent identical requests coalesce**: one upstream fetch per cache key per validity window, even when several clients arrive together after an expiry.
- **Additive observability**: the response carries `X-Cache: HIT|MISS`, mirroring what the tile proxy already reports. Request and response bodies are unchanged — there is **no BREAKING change** for clients.

### Non-goals

- No rate limiter, governor, cooldown or upstream `Retry-After` handling. That mitigation belongs to the separately planned `fix-windguru-upstream-rate-limiting` work described in `docs/windguru_api_rate_limit.md`; this change only reduces how often requests reach upstream.
- No cache invalidation endpoint, no `skipCache` query parameter on `/sensor-data`, and no purge API for the new cache (the tile proxy's `skipCache` is not extended here).
- No per-provider or per-sensor TTL overrides: one base TTL and one jitter window govern every provider.
- No disk persistence for this cache, so `EHCACHE_STORAGE_PATH` and `dev_setup/docker-compose.yml` stay as they are.
- No change to `/spots-data`, `/spots-data-dahab`, the tile endpoints or either existing tile cache.
- No client (watch/app) changes and no change to the `/sensor-data` JSON contract.

## Capabilities

### New Capabilities

- `sensor-data-response-cache`: what the backend must do when answering `POST /sensor-data` from a local cache — which key identifies a cached response, how long an entry stays valid, how that validity is randomised per entry, which outcomes may be cached and which never may, how concurrent requests share one upstream fetch, and what a client can observe.

### Modified Capabilities

<!-- None. `openspec/specs/` holds `onechip-wind-provider` (14 requirements) and `windguru-wind-provider`
     (2). Neither states anything about response caching, and this change alters no provider's parsing,
     unit-conversion, request-header or failure-classification requirement: a provider behaves exactly as
     its spec describes whether or not a cache entry answered the request that would have reached it. -->

## Impact

- **Code:** `com.vb.wingfoil.ProxyService` (cache lookup before the upstream call, effective window/count resolution moved ahead of the fetch, store on success); new small classes for the cache component and its stored entry (key composition, expiry computation); `com.vb.wingfoil.WindSensorConfig` (nested configuration properties for TTL and jitter window); `com.vb.wingfoil.WindSensorController` (surfaces the cache status without changing the body). No `WindDataProvider`, `BaseWindyDataProvider` or provider implementation is edited — that is the point of the change.
- **Configuration:** new `wind-sensor.sensor-data-cache.*` keys with `CACHE_TTL` and `CACHE_TTL_FLOATING_WINDOW` overrides, plus a new `ehcache.caches.sensor-data-cache` declaration under the existing `ehcache:` block in `src/main/resources/application.yml`. New env vars are optional; defaults apply when absent.
- **Dependencies:** none new. `micronaut-cache-core` and `micronaut-cache-ehcache` are already in `build.gradle`, and Ehcache already runs in production for the tile caches.
- **Tests:** `src/test/resources/application-test.yml` disables the cache so the existing `WindSensorControllerMockServerTest` scenarios keep asserting exactly one upstream call per request; a dedicated suite enables it with short TTLs to prove hit/miss behaviour, jitter bounds, key separation across providers, non-caching of failures and single-flight coalescing.
- **Operations / clients:** bodies stay identical, but a client polling faster than the TTL now receives the same readings until the entry expires — the intended consequence of caching, recorded as a trade-off in `design.md`.
- **Risk:** an expired-provider outage or an offline sensor can keep serving its last (possibly zero-valued) response for up to `ttl + window`; cache hits also hide upstream recovery. Mitigations and the deliberate choices are in `design.md`.
