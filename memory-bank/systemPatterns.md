# System Patterns

## Architecture Overview
Stateless REST API built on Micronaut 5 with a plugin-based provider architecture. No internal state or database - all data flows through from external APIs.

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
                                        +--> OsmTileService / TileCompositeService (OSM tile proxy + composites)
```

## Data Flow
1. HTTP request arrives at `WindSensorController`
2. Controller injects appropriate `WindDataProvider` implementation
3. Provider fetches data from external API via Apache HttpClient5
4. Response parsed and normalized into `WindReading` model
5. Data returned to client as JSON
6. Exceptions caught by `GlobalExceptionHandler`

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