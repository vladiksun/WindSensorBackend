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
- Response cache for `POST /sensor-data`: named Ehcache instance `sensor-data-cache`, heap-only, per-entry floating TTL
  (`ttl ± floating-window`, floor 1 s), per-key single-flight coalescing, `X-Cache: HIT|MISS` on the response — disabled
  in the test profile so request-level tests keep asserting one upstream call
- Test infrastructure dependencies configured (testcontainers, MockServer, JUnit Platform Launcher)
- Micronaut 5 migration completed successfully

## What's Left to Build
- [ ] Test implementation (testcontainers + MockServer dependencies added, but no test classes exist) — outdated: `src/test/java` now holds 23 test classes plus a `StubUpstreamServer` helper
- [ ] Additional wind data providers as needed
- [x] Caching layer for frequently requested data — shipped for `POST /sensor-data` (floating-TTL response cache, 2026-11-18) and earlier for tiles; a provider-request governor is still open (`docs/windguru_api_rate_limit.md`)
- [ ] Rate limiting for external API calls
- [ ] Comprehensive error response format
- [ ] OpenAPI annotations on controller endpoints
- [ ] CI/CD pipeline configuration

## Current Status
- **Phase**: Active development / MVP+
- **Version**: 0.1
- **Framework**: Micronaut 5.0.0 (migrated from 4.x)
- **Core functionality**: Implemented and stable
- **Testing**: 23 test classes plus the `StubUpstreamServer` helper; 140 tests in the current suite, of which only two fail — the pre-existing tiles assertions about composite optimization. The six newest classes cover the `/sensor-data` response cache (31 tests, all green)
- **Documentation**: Memory bank maintained, OpenAPI support added
- **OpenSpec**: 3 main specs (`onechip-wind-provider`, `sensor-data-response-cache`, `windguru-wind-provider`), all passing `--specs --strict`; no active changes — the floating-TTL cache change is archived with production verification still open
- **Recent activity**: Micronaut 5 migration, code refactoring, test infrastructure preparation

## Known Issues
- Two tiles tests fail for reasons unrelated to the response cache, both because they assume composite optimization is disabled by default while `application.yml` has carried `osm-tiles.optimization.enabled: true` since the archived tile-optimization change: `OsmTilesConfigurationTest.defaultsMatchApplicationYml` and `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`
- Production behaviour of the `/sensor-data` response cache is unverified — task 7.4 of the archived change was not performed (the code is staged but never committed or deployed), so `MISS` → `HIT` in production, tile-serving neutrality and reduced upstream volume are still assumed rather than measured
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

## Recent Progress — 1chip silent empty readings (2026-11-17, OpenSpec change `fix-onechip-silent-empty-readings`)

Implemented and deployed:
- `OneChipDataProvider` no longer assumes a fixed table layout. Column meaning comes from the header row (`colspan`
  expansion, caption matching), the recent table `#data_table` is tried before the grouped fallback `#group_table`, both are
  merged over the requested window in ascending timestamp order and deduplicated, and every unproductive answer is named:
  `NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR`. Future-dated rows are tolerated within a
  bounded window, clamped to now, or rolled back whole days when the whole page is shifted.
- `ProxyService` builds one shared upstream HttpClient with explicit bounds — connect 10 s, response 30 s, pool lease 10 s,
  automatic retries disabled so one `/sensor-data` request maps to exactly one upstream call — and provider headers now
  override the JSON defaults instead of appending duplicates. Exposed as `wind-sensor.upstream-*-timeout-seconds`.

Verification: `OneChipDataProviderTest` 22 cases (fixtures captured from the live pages), `WindguruDataProviderTest` 16,
`WindSensorControllerMockServerTest` 19, `WindSensorControllerUpstreamTimeoutTest` 1 (raw local socket that accepts TCP and
stays silent, because MockServer 5.15 cannot delay a response); all green with both compile gates and Spotless clean. Two
tiles tests still fail for pre-existing unrelated reasons.

Production result (the decisive finding): the deployed container logs `reason=NO_BODY, bodyLength=0` for both Dahab ids and
answers HTTP 200 with a single zero reading in ~0.4 s, because **1chip.ru returns `200` / `content-length: 0` to this
server's egress** for every sensor page — the site root works (36 KB), headers/cookies/HTTP-1.1/plain-HTTP variants change
nothing, and the same URL gives ~34 KB from a workstation at the same moment. Windguru station `15693` is refused from that
host too (`403 {"return":"error","message":"forbidden"}`), and the Windy mirror of a Dahab 1chip station
(`1chipru_e868e7f2bf47`, "Happy Kite/Soul Kiters_ Dahab lagoon (1chip.ru)") exists but publishes an empty `data` array. The
diagnostics half of the change works as designed; restoring the readings requires a source decision outside this repository.

Workaround survey (same day, third pass): the block covers only `/windt.php`. From the production host these 1chip paths still answer normally — `/wind` sensor directory (66 KB, `<OPTION VALUE="id,lat,lon,lastSeenUnix,type,name">`, id `0264` = "Dahab - Assala 2 EGYPT"), `/active.php?i=0264` → the sensor's last-packet unix time (`1794934731`, a usable liveness probe), `/img_php.php?i=0264` → a 32 KB JPEG chart of the same readings, and the widget/SEF pages (identical byte sizes to a clean egress). The only *structured* alternative that works from this host today is fetching through another egress: `https://r.jina.ai/https://1chip.ru/windt.php?id=0264` returned 2 767 bytes of markdown holding the whole past-data table (`23:26 ↓ 5.3 6.2 7.2 31 NNW 351° 23.7`, rows every ~3 min). Public CORS relays (allorigins, codetabs, corsproxy) were unusable (522/522/401), and Windguru station `15693` carries no data even from a clean egress, so it is not a mirror source.

Archive status (2026-11-18): the change is archived to `openspec/changes/archive/2026-11-18-fix-onechip-silent-empty-readings/`
with all 26 tasks complete. Its single delta spec was synced into a new main spec,
`openspec/specs/onechip-wind-provider/spec.md` — Purpose plus 14 requirements / 33 scenarios copied verbatim, no MODIFIED,
REMOVED or RENAMED operations — which re-baselines the two onechip deltas archived on 2026-09-10 and 2026-09-21 that were
never synced into `openspec/specs/`. `openspec validate --specs` now reports 2 passed, 0 failed (`onechip-wind-provider` 14
requirements, `windguru-wind-provider` 2), and there are no active OpenSpec changes left in this repository.

## Recent Progress — floating-TTL response cache for `/sensor-data` (2026-11-18, OpenSpec change `add-sensor-data-floating-ttl-cache`)

Implemented, tested and documented locally; **not deployed**, so task 7.4 (production verification) is the only open item
of the change's 26.

What was built:

- New package `com.vb.wingfoil.cache`: `SensorDataCacheProperties` (bound to `wind-sensor.sensor-data-cache`),
  `FloatingTtlCalculator`, `CachedSensorReadings`, `CacheStatus`, `SensorDataOutcome`, `SensorDataResponseCache`
  (the named Ehcache instance `sensor-data-cache`) and `SensorDataSingleFlight`.
- `ProxyService.requestTimedReadings` resolves the effective `readingWindow` / `numberOfReadings` **before** the call,
  looks the key `<provider>/<sensorId>/<window>/<count>` up, coalesces concurrent misses per key, stores only successful
  parses, and returns `Try<SensorDataOutcome>`. `WindSensorController.getSensorData` answers with `X-Cache: HIT|MISS` and
  keeps its existing status mapping and JSON error bodies untouched. No provider knows the cache exists.
- Production configuration: `wind-sensor.sensor-data-cache` (`SENSOR_DATA_CACHE_ENABLED`, `CACHE_TTL=5m`,
  `CACHE_TTL_FLOATING_WINDOW=1m`) and `ehcache.caches.sensor-data-cache` with a 200-entry heap tier and **no** disk tier,
  so nothing new lands under `ehcache.storage-path` and `dev_setup/docker-compose.yml` is unchanged.
- The test profile keeps caching off (`application-test.yml`), which is why every pre-existing request-level suite still
  asserts exactly one upstream call per request.

Findings worth remembering:

- Micronaut's Ehcache integration validates every value against `Serializable` **at write time**, so a heap-only cache is
  not exempt: `CachedSensorReadings` implements it, and therefore its payload record `SensorDataDTO` must as well. The
  design document originally claimed the opposite and has been corrected.
- MockServer 5.15 cannot delay a response, so the caching-enabled suites run against a deterministic loopback stub
  (`src/test/java/com/vb/wingfoil/StubUpstreamServer.java`) that counts calls per path, answers `503` on one path and
  holds another past `upstream-response-timeout-seconds` to exercise the transport-failure case. Its handlers run on their
  own threads, and a stalled handler closes its exchange when its delay ends — otherwise the connection abandoned by the
  timed-out call stays open and the *next* request fails on a dead socket instead of proving whether a second provider
  call happened (the original failure mode of `anUpstreamThatAcceptsTheConnectionAndThenStallsIsNotCached`).
- A successful provider call that yields no usable readings produces **one zero-valued reading**, not an empty list, and
  that single reading is what gets cached and replayed as `HIT`.

Verification: 31 tests across `FloatingTtlCalculatorTest` (7), `SensorDataResponseCacheTest` (6),
`SensorDataSingleFlightTest` (4), `SensorDataCoalescingTest` (4), `SensorDataCachingDisabledTest` (2) and
`WindSensorControllerResponseCacheTest` (8) — green on two consecutive runs; `spotlessCheck`, `compileJava` and
`compileTestJava` clean; `openspec validate add-sensor-data-floating-ttl-cache --strict` reports the change valid with
25 of 26 tasks complete. The full `./gradlew test` suite runs 140 tests in 22 classes and fails only in the two
pre-existing tiles assertions about composite optimization, which this change does not touch.

Archived 2026-11-18 to `openspec/changes/archive/2026-11-18-add-sensor-data-floating-ttl-cache/` with **25 of 26 tasks
complete**. The open task is 7.4 — production verification — and the archived `tasks.md` states plainly that it was **not
performed**: the implementation is staged but uncommitted and undeployed, so only local verification exists. The delta spec
was synced into a new main spec, `openspec/specs/sensor-data-response-cache/spec.md` (10 requirements / 27 scenarios), and
`openspec validate --specs --strict` now passes for all three capabilities (`onechip-wind-provider`,
`sensor-data-response-cache`, `windguru-wind-provider`) with no active changes left in `openspec/changes/`.

