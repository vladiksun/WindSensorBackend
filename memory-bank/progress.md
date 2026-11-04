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
- Speeds are published in knots (this provider converts them to metres per second — see the unit-conversion section
  below); timestamps are epoch seconds from `unixtime` (`datetime` text and
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

## Recent Progress — Windguru unit conversion (2026-11-04, OpenSpec change `convert-windguru-knots-to-ms`)

What changed:
- `WindguruDataProvider` now reports **metres per second**. `METRES_PER_SECOND_PER_KNOT = 1_852d / 3_600d`
  (0.514444… m/s) plus a package-private `knotsToMetresPerSecond(float)` convert min/avg/gust **exactly once**, inside
  `toMeasurements(...)`, rounding half-up to two decimals; `wind_direction` and `unixtime` pass through untouched, and
  neither `mapToDTO` nor the shared window reducer converts anything.
- `WindguruMeasurement` documents min/avg/gust as metres per second (converted from Windguru's knots).
- **BREAKING for clients**: every windguru number is ~48.6 % smaller than before it (23.1 kn → 11.88 m/s), so any client
  or spot configuration that assumed knots must be told.

Live re-probe facts for station 15693 (2026-11-04):
- `station_data` publishes **knots** — the station page renders the same payload as "25 knots — max: 28.3, min: 20.".
- `unit_speed=mps`, `units=ms` and `speed_unit=ms` each return a byte-identical payload: Windguru cannot be asked for
  metric speeds, so the conversion has to live in this backend.
- End-to-end check against a running app: upstream row `min 21.7 / avg 25.8 / max 29.1` kn came back from
  `POST /sensor-data` (`"provider": "windguru"`, `"id": "15693"`) as `11.16 / 13.27 / 14.97` m/s, with direction and
  epoch timestamps unchanged.

Verification: `WindguruDataProviderTest` 16 cases (the 12 existing ones plus 4 unit tests for the factor, the rounding,
zero and speed ordering) and `WindSensorControllerMockServerTest` 16 cases all pass; the MockServer fixture stays in knots
while its assertions are metres per second (oldest row 10.39 / 11.88 / 13.38, newest row 10.70 / 12.50 / 14.76); spotless
and both compile gates clean; `openspec validate convert-windguru-knots-to-ms --strict` valid.

Open unit questions (design.md):
- `SensorDataDTO` is still unit-less, so `/sensor-data` is now provider-dependent: `windguru` reports m/s while `windy`,
  `neduet` and `onechip` mirror whatever their upstream publishes. Whether the response should declare its unit, or whether
  the other providers must be audited/normalized too, is out of scope for this change.
- Resolved at archive time (2026-11-04): `openspec archive` synced this change's two ADDED requirements and created
  `openspec/specs/windguru-wind-provider/spec.md`, so the capability keeps exactly one normalization requirement — the
  metres-per-second wording. The older `add-windguru-wind-provider` archive is deliberately left unsynced; syncing it would
  reintroduce its obsolete "wind speed in knots" wording.
