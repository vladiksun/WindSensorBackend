# Progress

## What Works
- Core Micronaut 5 application boots and runs on port 8080
- REST API endpoints functional:
  - `POST /sensor-data` - Fetches wind readings from providers
  - `GET /spots-data` - Fetches available sensor locations (with optional `isDebug` flag)
  - `GET /spots-data-dahab` - Fetches Dahab-specific locations (with optional `isDebug` flag)
- Provider plugin system with Windy, Neduet, 1chip (HTML via Jsoup) and Windguru (columnar JSON) implementations
- Provider-declared upstream headers: `WindDataProvider.requestHeaders(sensorId)` applied by `ProxyService`
- Multiple Windy provider variants via `BaseWindyDataProvider` abstract base class
- Configuration binding from `application.yml`
- External spots configuration loaded from GitHub
- Docker image building via Jib plugin
- Code formatting via Spotless plugin (Palantir Java Format)
- Metrics and health check endpoints enabled
- Virtual threads for I/O-bound request handling
- OpenAPI/Swagger UI enabled at `/swagger/**` and `/swagger-ui/**`
- Global exception handling via dedicated `GlobalExceptionHandler` bean
- Test infrastructure dependencies configured (testcontainers, MockServer, JUnit Platform Launcher)
- Micronaut 5 migration completed successfully

## What's Left to Build
- [ ] Test implementation (testcontainers + MockServer dependencies added, but no test classes exist)
- [ ] Additional wind data providers as needed
- [ ] Caching layer for frequently requested data
- [ ] Rate limiting for external API calls
- [ ] Comprehensive error response format
- [ ] OpenAPI annotations on controller endpoints
- [ ] CI/CD pipeline configuration

## Current Status
- **Phase**: Active development / MVP+
- **Version**: 0.1
- **Framework**: Micronaut 5.0.0 (migrated from 4.x)
- **Core functionality**: Implemented and stable
- **Testing**: Infrastructure prepared, tests not written (0% coverage)
- **Documentation**: Memory bank maintained, OpenAPI support added
- **Recent activity**: Micronaut 5 migration, code refactoring, test infrastructure preparation

## Known Issues
- Test Java directory is completely empty (`src/test/java/com/vb/wingfoil/` has no files)
- `WindSensorBackendTest.java` was removed in latest refactor
- SSL configuration requires keystore for production (`SSL_ENABLED:true` by default)
- Security module is commented out but configurable via `SECURITY_ENABLED`

## Evolution of Project Decisions
- Started with Micronaut 4.x for AOT and fast startup
- Migrated to Micronaut 5.0.0 for latest framework improvements (2026-06-07)
- Chose Java 25 for virtual threads support
- Selected Vavr for functional error handling over traditional exceptions
- Externalized spots configuration to separate GitHub repo for easy maintenance
- Stateless design chosen to simplify deployment and scaling
- Added OpenAPI support for API documentation (2026-06-05)
- Added testcontainers + MockServer for test infrastructure (2026-06-06)
- Moved exception handling to dedicated `@Controller` bean for cleaner separation
- MockServer client downgraded from 6.1.0 to 5.15.0 for compatibility
- JUnit Platform Launcher added as testRuntimeOnly dependency (2026-06-07) for proper test discovery

## Recent Progress — Windguru provider (2026-11-04, OpenSpec change `add-windguru-wind-provider`)

What was built:
- `com.vb.wingfoil.provider.impl.WindguruDataProvider` (provider name `windguru`, config key
  `wind-sensor.wind-providers.windguru.url`) extending `BaseWindyDataProvider`, so windowing and the
  number-of-readings reduction stay shared with every other provider.
- Wire models `com.vb.wingfoil.response.windguru.WindguruStationData` (boxed column arrays) and
  `WindguruMeasurement` (normalized reading built by unzipping the columns).
- Shared plumbing: `WindDataProvider.requestHeaders(String sensorId)` default hook, applied by
  `ProxyService.requestTimedReadings` on top of the existing `Accept: application/json`.

Live-endpoint findings for station 15693 (Dahab – Assalah), re-verified on 2026-11-04:
- `GET https://www.windguru.cz/int/iapi.php?q=station_data&id_station=15693&avg_minutes=1&graph_info=1&from=<ISO-UTC>&to=<ISO-UTC>`
  answers **200** with a JSON object of five equal-length arrays (24 h window → 358 points each).
- The same request **without `Referer` answers 401**; `Referer: https://www.windguru.cz/station/15693` is the only
  header that matters — cookies and `X-WG-Token` were not needed, and no User-Agent avoided the 401.
- Speeds are already knots (no conversion); timestamps are epoch seconds from `unixtime` (`datetime` text and
  `tzoffset` are station-local and deliberately ignored — this avoids the DST/timezone reconstruction 1chip needs).
- Gaps appear as `null` inside the numeric arrays; a valid but empty range answers HTTP 200 with body `[]`.
  Both shapes normalize to a single empty reading rather than zero-speed readings or an error.

Verification: `WindguruDataProviderTest` (12 cases) and `WindSensorControllerMockServerTest` (16 cases, 6 of them new:
header plumbing both directions, windguru normalization, Referer arrival at upstream, gap/`[]` handling, upstream 401
failure) all pass; spotless and both compile gates clean. The older "no test classes exist" notes above are outdated.

Deferred / open items:
- Whether the fixed 24 h look-back (`LOOK_BACK_SECONDS`) should become a `history-hours` config field for longer windows.
- Whether to drop `graph_info=1` from the configured URL (payload noise only).
- Confirming server-side use of `iapi.php` is acceptable at production request volume (no published rate limit).
- Rollout needs the external spots config to point Dahab – Assalah at `"provider": "windguru"`, `"id": "15693"`
  (`wingfoil-spots.json` lives in a separate repo and was intentionally not touched here).

