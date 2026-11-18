# System Patterns

## Architecture Overview
Stateless REST API built on Micronaut 5 with a plugin-based provider architecture. No database and no persisted application state - all data flows through from external APIs. The only in-process state is short-lived, bounded and restart-free: the Ehcache-backed caches behind the tile proxy and (since `add-sensor-data-floating-ttl-cache`) behind `POST /sensor-data`.

## Key Technical Decisions
- **Micronaut 5** for compile-time DI, AOT optimization, fast startup
- **Java 25** with virtual threads for I/O-bound concurrency
- **Vavr** for functional error handling (Try, Option monads)
- **Plugin pattern** for provider extensibility
- **Template Method pattern** in `BaseWindyDataProvider` for provider variants
- **Strategy pattern** via `WindDataProvider` interface
- **Docker-native** deployment via Jib plugin

## Component Relationships
```
Client --> WindSensorController --> ProxyService --> WindDataProvider (interface)
                                        |              |
                                        |              +--> BaseWindyDataProvider (abstract: windowing, reduction)
                                        |              |        +--> WindyDataProvider
                                        |              |        +--> NeduetDataProvider
                                        |              |        +--> OneChipDataProvider   (HTML table via Jsoup)
                                         |              |        +--> WindguruDataProvider  (columnar JSON arrays, knots → m/s)
                                        |              |
                                        |              +--> requestHeaders(sensorId) hook -> provider-specific headers
                                        |
                                        +--> SensorDataResponseCache (Ehcache "sensor-data-cache", per-entry floating TTL)
                                         |        +--> FloatingTtlCalculator (ttl ± floating-window, floored at 1 s)
                                         +--> SensorDataSingleFlight  (per-key fetch coalescing)
                                         |
                                         +--> OsmTileService / TileCompositeService (OSM tile proxy + composites)
```

## Data Flow
1. HTTP request arrives at `WindSensorController`
2. `ProxyService.requestTimedReadings` resolves the provider by name **and** the effective `readingWindow` / `numberOfReadings` before any call, then asks `SensorDataResponseCache` for that key; a still-valid entry answers immediately and the response carries `X-Cache: HIT`
3. On a miss `SensorDataSingleFlight` runs the fetch once per key: one bounded Apache HttpClient5 call (provider-declared `requestHeaders` layered over `Accept: application/json`, automatic retries disabled)
4. The provider parses and normalizes the body (`WindReading` / `SensorDataDTO`); the parsed list is stored with a freshly drawn expiry and the response carries `X-Cache: MISS`
5. Data returned to client as JSON
6. Exceptions caught by `GlobalExceptionHandler`; a call that failed caches nothing and its throwable reaches the handler unchanged

## Key Patterns

### Provider Plugin Pattern
- `WindDataProvider` interface defines contract
- Implementations registered as Micronaut beans
- Configuration-driven provider selection
- Easy to add new providers without modifying existing code

### Template Method Pattern
- `BaseWindyDataProvider` defines algorithm skeleton
- Subclasses override specific steps (URL building, parsing)
- Shared logic: HTTP execution, error handling, logging

### Functional Error Handling
- Vavr `Try` for exception-safe operations
- Vavr `Option` for nullable values
- Eliminates null pointer exceptions
- Composable error handling chains

### Provider-Declared Upstream Headers
- `WindDataProvider.requestHeaders(sensorId)` is a default method returning `Map.of()`; `ProxyService` layers whatever
  a provider declares on top of the shared `Accept: application/json` header
- `WindguruDataProvider` overrides it with `Referer: https://www.windguru.cz/station/<id>` because Windguru's
  `iapi.php` data endpoints answer 401 to header-less calls (verified live, every User-Agent tried)
- Providers that declare nothing are untouched, so an upstream-specific header never changes another provider's call

### Provider-Side Unit Normalization
- Windguru publishes **knots**; `WindguruDataProvider.knotsToMetresPerSecond(float)` converts each published speed with
  `METRES_PER_SECOND_PER_KNOT = 1852/3600` (0.514444… m/s), rounded half-up to two decimals, and it is called **exactly
  once** per value — while the columns are unzipped in `toMeasurements(...)`. Direction (degrees) and `unixtime`
  (epoch seconds) never pass through it, and nothing downstream (`mapToDTO`, `buildTimedReadings`,
  `getReadingsByInterval`) converts again.
- The conversion lives in the provider, not in `BaseWindyDataProvider` or `SensorDataDTO`, so adding it changed no other
  provider's numbers. Consequence to remember: `SensorDataDTO` is unit-less, so the reported unit is provider-dependent —
  `windguru` is metres per second, while `windy`, `neduet` and `onechip` still mirror their upstream unit.

### Response Cache With A Floating TTL (`POST /sensor-data`, OpenSpec change `add-sensor-data-floating-ttl-cache`)
- Caching happens in exactly one place — `ProxyService.requestTimedReadings` — so no `WindDataProvider` knows a cache exists and any provider added later is cached by the same code. Order: resolve provider → resolve effective window/count → build key → valid entry answers as `HIT`, otherwise coalesced fetch, parse, store, answer as `MISS`.
- Cache key is the readable string `<provider>/<sensorId>/<readingWindowSeconds>/<numberOfReadings>` (`SensorDataResponseCache.cacheKey`), because window and count change the response body.
- Expiry travels inside the value: `CachedSensorReadings(readings, storedAtEpochMillis, expiresAtEpochMillis)`. Ehcache 3 TTL is a cache-level setting and micronaut-cache exposes only tiers (`heap.max-entries`, `disk.max-size`), so a per-entry random TTL cannot be expressed in configuration. Same pattern as `CachedTile` / `CachedComposite`.
- Time-to-live, never time-to-idle: a lookup compares the stored expiry against now, treats an expired entry as absent and removes it, and never extends an entry — a hot poll cannot pin one forever.
- `FloatingTtlCalculator` draws one jitter per write: uniform over `[0, floating-window]` inclusive with a random sign, added to `ttl`, floored at 1 000 ms. Purpose: refetch instants stop being the fixed interval a periodic poller is fingerprinted by.
- What is and is not cached: every failure (`HttpClientError`, `ParseResponseException`, "provider not found", a stalled connection) stores nothing and propagates unchanged; a successful parse is stored **even when it produced no usable readings** — that is the abuse protection for an offline sensor, whose single zero-valued reading then answers repeat polls.
- `SensorDataSingleFlight` coalesces per key: `putIfAbsent` elects one leader, only the leader runs the fetch, it removes its own future in a `finally`, waiters bound their own wait over the configured upstream timeouts plus a margin, and the leader's throwable reaches every waiter as the identical instance. Keys never wait on each other.
- Values must implement `Serializable`: Micronaut's Ehcache integration validates every value against `Serializable` when writing it, so a heap-only cache is not exempt (`CachedSensorReadings` and its payload record `SensorDataDTO` do).
- Configuration: `wind-sensor.sensor-data-cache.enabled|ttl|floating-window` (`SENSOR_DATA_CACHE_ENABLED`, `CACHE_TTL`, `CACHE_TTL_FLOATING_WINDOW`; defaults `true`, `5m`, `1m`) plus `ehcache.caches.sensor-data-cache.heap.max-entries: 200` with **no** disk tier, so nothing new is written under `ehcache.storage-path`.
- Observability and rollback: the client sees the outcome as `X-Cache: HIT|MISS`; `enabled: false` (which `application-test.yml` sets) restores exactly one upstream call per request without a rebuild.

### Configuration Externalization
- Provider URLs in `application.yml`
- Spots configuration loaded from GitHub
- Environment variables for sensitive config
- Debug mode toggle via query parameters

## Critical Implementation Paths
- `WindSensorController.handleWindSensorRequest()` - Main request handler
- `BaseWindyDataProvider.executeRequest()` - HTTP execution with retry logic
- `GlobalExceptionHandler` - Centralized error response formatting
- `SpotsConfiguration` - External configuration loading and parsing

## Deployment Architecture
- Single Docker container per instance
- Horizontal scaling via container replication
- Load balancer in front (not part of this project)
- No shared state between instances