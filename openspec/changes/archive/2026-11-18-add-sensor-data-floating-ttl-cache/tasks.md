# Tasks

## 1. Configuration and expiry primitives

- [x] 1.1 Add `SensorDataCacheProperties` bound to `wind-sensor.sensor-data-cache` with `ttl` (`Duration`, default `5m`), `floatingWindow` (`Duration`, default `1m`) and `enabled` (default `true`).
- [x] 1.2 Implement the expiry calculator from design §6: per-entry jitter drawn uniformly from `[0, window]` with a random sign, applied to the base TTL, floored at 1 second.
- [x] 1.3 Unit-test the calculator: every draw lands inside `ttl ± window`; expiries are not all identical; `window = 0` gives exactly the base TTL; `window ≥ ttl` still yields at least 1 second. — `FloatingTtlCalculatorTest`, 7 cases.

## 2. Cache component

- [x] 2.1 Add the cached-value record (readings plus `storedAtEpochMillis` and `expiresAtEpochMillis`) as an immutable record. — `CachedSensorReadings`; it also had to implement `Serializable` because Micronaut's Ehcache validates every value against `Serializable` at write time even for a heap-only cache, which required the same of its payload record `SensorDataDTO` (see design §5).
- [x] 2.2 Add the cache component injecting `@Named("sensor-data-cache") SyncCache<…>`, with key composition `<provider>/<sensorId>/<window>/<count>`, lookup that treats an expired entry as absent and removes it, and store that asks the calculator for the expiry. — `SensorDataResponseCache` (`CACHE_NAME = "sensor-data-cache"`).
- [x] 2.3 Unit-test key composition: different providers, sensor ids, windows and counts never share an entry; a hit returns exactly the readings stored by the fetch. — `SensorDataResponseCacheTest`, 6 cases.

## 3. Single-flight coalescing

- [x] 3.1 Implement the per-key in-flight map from design §7: `putIfAbsent` selects the leader, only the leader runs the fetch, the leader removes its own future in a `finally`, waiters bound their own wait by a margin over the configured upstream timeouts. — `SensorDataSingleFlight`.
- [x] 3.2 Unit-test coalescing: concurrent identical keys produce exactly one supplier call and equal results; different keys do not wait on each other; a failure reaches every waiter as the same throwable. — `SensorDataSingleFlightTest` (4) plus `SensorDataCoalescingTest` (4) through `ProxyService`.

## 4. Wire caching into the shared fetch path

- [x] 4.1 Extract the effective `readingWindow` / `numberOfReadings` resolution in `ProxyService` into one step that runs **before** the request, keeping the current precedence (request value → defaults → sensor configuration) and the existing default constants. — `ResponseHandlerContext` no longer re-resolves them.
- [x] 4.2 Insert lookup-and-store around the existing provider call in `requestTimedReadings`, gated on `enabled`, without changing URL construction, the provider `requestHeaders` layering, or the single bounded HTTP call.
- [x] 4.3 Cache only after a successful parse; propagate every throwable (`HttpClientError`, `ParseResponseException`, "provider not found") unchanged so failure statuses and bodies stay as they are today.
- [x] 4.4 Return the readings together with a `CacheStatus` (`HIT` / `MISS`) from `requestTimedReadings`. — `SensorDataOutcome(readings, status)`.
- [x] 4.5 Update `WindSensorController.getSensorData` to set `X-Cache: HIT|MISS` on a mutable response, keeping its existing 400 / 502 mapping and JSON error bodies; add the header to the endpoint's OpenAPI documentation if that endpoint documents response headers. — Header set on `MutableHttpResponse`. The OpenAPI half is not applicable: `/sensor-data` carries no `@Operation`/`@ApiResponse` annotations today (only `TileProxyController` documents response headers), so there is nothing to extend; adding endpoint-wide OpenAPI annotations stays a separate task.

## 5. Application configuration

- [x] 5.1 Add `ehcache.caches.sensor-data-cache` with a bounded heap tier and **no** disk tier, next to `osm-tiles` and `osm-composites`. — `heap.max-entries: 200`.
- [x] 5.2 Add the `wind-sensor.sensor-data-cache` block with `ttl: ${CACHE_TTL:-5m}` and `floating-window: ${CACHE_TTL_FLOATING_WINDOW:-1m}`, mirroring the existing `${CACHE_TTL:-}` placeholder style used for tiles. — plus `enabled: ${SENSOR_DATA_CACHE_ENABLED:true}` for the rollback switch.
- [x] 5.3 Confirm no new file is written under `ehcache.storage-path` after a run, so `dev_setup/docker-compose.yml` needs no change. — Verified two ways: `sensor-data-cache` declares only a heap tier (Micronaut's Ehcache disk store exists only where a `disk` tier is declared), and after the caching suites ran the test storage path (`%TEMP%/windsensorbackend-test-ehcache`) contains nothing but Ehcache's own `.clean` marker — no `sensor-data-cache` directory or persist file. No production run was performed as part of implementation (see 7.4).

## 6. Tests

- [x] 6.1 Add `wind-sensor.sensor-data-cache.enabled: false` to `src/test/resources/application-test.yml`, then confirm all nineteen existing MockServer scenarios still pass with their unchanged "exactly one upstream call" expectations, including the upstream-timeout test. — `WindSensorControllerMockServerTest` (19) and `WindSensorControllerUpstreamTimeoutTest` green unchanged; `SensorDataCachingDisabledTest` (2) asserts a repeated identical request still reaches upstream twice with no `X-Cache`-driven reuse.
- [x] 6.2 Add a caching-enabled MockServer suite (test properties: caching on, short TTL, zero floating window): first request `MISS`, second identical request `HIT` with an equal body and no additional upstream call; a request after expiry is a `MISS` that refetches. — Implemented against the deterministic loopback stub `StubUpstreamServer` instead of MockServer (design §9 explains why), with `ttl=30s`, `floating-window=0s`.
- [x] 6.3 Add the failure cases: upstream `503` and a transport timeout cache nothing, keep their current status and error body, and the next identical request still reaches upstream. — Both covered; the timeout case uses a stub path that accepts the connection and holds the response past `upstream-response-timeout-seconds=1`.
- [x] 6.4 Add the successful-but-empty case: a `200` response that yields no usable readings stores the single zero-valued reading, and the next identical request returns it as `HIT` with no extra upstream call.
- [x] 6.5 Add the concurrency cases: a burst of identical requests triggers exactly one upstream call and every response carries equal readings; two different sensors requested together are fetched independently. — `WindSensorControllerResponseCacheTest` (end-to-end) and `SensorDataCoalescingTest`.
- [x] 6.6 Add a transparency case using an already-registered test probe provider: it is cached with no change to the caching code, and its declared headers (for example `onechip`'s HTML `Accept`) still appear on a miss. — The suite's own `HeaderProbeProvider` (`@Requires(property = "spec.name")`, config key `wind-sensor.wind-providers.probe.url`) is cached by the same code, and its declared `X-Test-Header` is asserted at the stub alongside exactly one `Accept`.

## 7. Validation, documentation and rollout

- [x] 7.1 Run `./gradlew -q spotlessCheck` (with `spotlessApply` then a re-check if it fails), then `./gradlew -q :compileJava` and `./gradlew -q :compileTestJava`. — `spotlessApply` reformatted the new files; `spotlessCheck` now passes, both compile gates clean.
- [x] 7.2 Run the targeted new tests first (`--tests` per class), then the full `./gradlew test` suite. — Targeted: 31 tests across the six new classes green on two consecutive runs; `WindSensorControllerMockServerTest` (19), `WindSensorControllerUpstreamTimeoutTest`, `OneChipDataProviderTest` (22) and `WindguruDataProviderTest` (16) unchanged. Full suite: 140 tests in 22 classes, exactly two failures — `OsmTilesConfigurationTest.defaultsMatchApplicationYml` and `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`, both asserting composite optimization is disabled by default while `application.yml` has carried `osm-tiles.optimization.enabled: true` since the archived tile-optimization change. Neither touches `/sensor-data`; both fail identically without this change's files.
- [x] 7.3 Update `memory-bank/systemPatterns.md` with the new caching pattern, cache name and configuration keys, and `memory-bank/progress.md` with what shipped; check `docs/API_SPECIFICATION.md` for a `/sensor-data` response description and add the `X-Cache` header there if present. — `systemPatterns.md` gained the "Response Cache With A Floating TTL" pattern plus updated architecture/data-flow sections, `progress.md` and `activeContext.md` record the change. `docs/API_SPECIFICATION.md` does not exist in this repository (`docs/` holds only `windguru_api_rate_limit.md`), so there is no `/sensor-data` response description to extend.
- [ ] 7.4 **Not performed — production verification still outstanding.** After a deploy, two identical polls of one sensor within the TTL must return `MISS` then `HIT`, tile serving must be unaffected, and upstream call volume must drop for cached sensors. As of 2026-11-18 this change is staged but **uncommitted and undeployed**, so only local verification exists (see 7.2); nothing in this change claims the production check has happened. Tracked afterwards in `memory-bank/activeContext.md`.
