# Active Context

## Current Work Focus
- **Floating-TTL response cache for `/sensor-data`** — OpenSpec change `add-sensor-data-floating-ttl-cache` is **implemented and locally verified** (2026-11-18): 25 of its 26 tasks complete, `openspec validate add-sensor-data-floating-ttl-cache --strict` valid, 31 new tests green on three consecutive runs, Spotless and both compile gates clean. Full `./gradlew test`: 140 tests in 22 classes with only two failures, both pre-existing tiles assertions about composite optimization (`OsmTilesConfigurationTest.defaultsMatchApplicationYml`, `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`) that this change does not touch. What shipped: `com.vb.wingfoil.cache` (`SensorDataCacheProperties`, `FloatingTtlCalculator`, `CachedSensorReadings`, `CacheStatus`, `SensorDataOutcome`, `SensorDataResponseCache`, `SensorDataSingleFlight`), the cache wired into `ProxyService.requestTimedReadings` (which now returns `Try<SensorDataOutcome>`), `X-Cache: HIT|MISS` on the endpoint, `wind-sensor.sensor-data-cache` plus a heap-only `ehcache.caches.sensor-data-cache`, and caching switched off in the test profile so every pre-existing suite keeps its "exactly one upstream call" assertion. Only task 7.4 — production verification (`MISS` then `HIT`, tiles unaffected, upstream volume down) — is open, and it was **not performed**: the change was archived anyway with that gap written into its `tasks.md`. **Archived** (2026-11-18) to `openspec/changes/archive/2026-11-18-add-sensor-data-floating-ttl-cache/`, its delta synced into the project's third main spec `openspec/specs/sensor-data-response-cache/spec.md` (10 requirements / 27 scenarios; `openspec validate --specs --strict`: 3 passed, and `openspec list` now reports no active changes). All files are `git add`ed; nothing committed or deployed yet.
- **1chip silent empty readings** — OpenSpec change `fix-onechip-silent-empty-readings` implemented, deployed, verified in production (2026-11-17) and **archived** (2026-11-18) to
  `openspec/changes/archive/2026-11-18-fix-onechip-silent-empty-readings/`. Its delta was synced into the project's second main spec,
  `openspec/specs/onechip-wind-provider/spec.md` (14 requirements / 33 scenarios), re-baselining the two earlier onechip archives that had never been synced; `openspec validate --specs` passes for both capabilities. Header-driven parsing, named failure reasons (`NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR`), bounded future-timestamp correction and a bounded non-retrying upstream HttpClient are live on the server. Production outcome: 1chip.ru answers `200` with **`content-length: 0`** to this host for every `windt.php?id=…` while serving the same page (~34 KB) to a residential client, so the two Dahab sensors report "no data" for reasons outside this repository; Windguru station `15693` is also refused from that host (`403 forbidden`). Details in `openspec/changes/archive/2026-11-18-fix-onechip-silent-empty-readings/tasks.md` group 9.
- **Windguru unit conversion** — OpenSpec change `convert-windguru-knots-to-ms` implemented, verified and **archived** to
  `openspec/changes/archive/2026-11-04-convert-windguru-knots-to-ms/`. Its two ADDED requirements were synced, creating the
  first main spec of this project, `openspec/specs/windguru-wind-provider/spec.md` (`openspec validate --all --strict`:
  1 passed, 0 failed). Implementation is still uncommitted.
- **Windguru wind provider** — archived as `2026-11-04-add-windguru-wind-provider`; its own spec delta was never synced and
  that is now intentional: the capability spec above restates that requirement with metres-per-second wording and supersedes
  it, so re-syncing the older archive would only reintroduce the obsolete "wind speed in knots" wording.
- **Test Infrastructure Preparation** - Finalizing test dependencies for JUnit Platform compatibility
- **Micronaut 5 Migration** - Completed migration from Micronaut 4.x to Micronaut 5.0.0 (commit `56bba3f`)
  - Updated `io.micronaut.application` plugin to version 5.0.0
  - Updated `io.micronaut.aot` plugin to version 5.0.0
  - Verified application compatibility with Micronaut 5 runtime

## Recent Changes
1. **Add junit-platform-launcher** (2026-06-07, commit `90cb6ef`) - Added `testRuntimeOnly` dependency for JUnit Platform test execution support
2. **Refactoring** (commit `531a836`) - Code cleanup and structure improvements
3. **Update memory bank** (commit `24f1af6`) - Previous documentation update
4. **Migrate to Micronaut 5** (commit `56bba3f`) - Major framework upgrade
5. **Refactor tests** (commit `728968e`) - Test infrastructure preparation, testcontainers + MockServer dependencies added
6. **Add OpenAPI support** (commit `5d3e37f`) - Swagger UI documentation enabled
7. **Floating-TTL response cache for `/sensor-data`** (2026-11-18, OpenSpec change `add-sensor-data-floating-ttl-cache`, uncommitted) - `com.vb.wingfoil.cache` package, per-entry floating TTL in front of every provider fetch, per-key single-flight coalescing, `X-Cache: HIT|MISS`, heap-only `sensor-data-cache` Ehcache instance, caching disabled in the test profile

## Next Steps
- [ ] Write actual test classes (test infrastructure is ready but `src/test/java` is empty)
- [ ] Add OpenAPI annotations to controller endpoints for proper API documentation
- [x] Implement caching layer for frequently requested data — done for `POST /sensor-data` by `add-sensor-data-floating-ttl-cache` (and earlier for tiles); the provider-request governor in `docs/windguru_api_rate_limit.md` is still open and may reuse this cache (design Open Questions)
- [ ] Add rate limiting for external API calls
- [ ] Create comprehensive error response format
- [ ] Additional wind data providers as needed
- [ ] Windguru rollout follow-ups (deferred, see design.md Open Questions): decide whether the fixed 24 h look-back
  becomes a `history-hours` config field; whether to drop `graph_info=1`; confirm server-side use of `iapi.php` is
  acceptable at expected request volume; **reconsider** pointing Dahab – Assalah at `"provider": "windguru"`,
  `"id": "15693"` — measured on the production host (2026-11-17) that endpoint answers `403 {"return":"error",
  "message":"forbidden"}` even with browser headers, so it is not a working substitute there; decide later whether
  `/spots-data` should expose Windguru station metadata
- [ ] Commit the windguru conversion: provider + tests + archive move + the new main spec are all staged/unstaged work
  that is intentionally not committed yet
- [ ] Unit questions raised by the windguru conversion (design.md – Open Questions): should `/sensor-data` declare its
  unit explicitly, and must `windy` / `neduet` / `onechip` be audited or normalized so the API is not mixed-unit?
- [ ] Announce the breaking numeric change (~48.6 % smaller wind values for `windguru` sensors) to clients and to the owner
  of the external spots configuration before pointing more spots at this provider
- [ ] Commit and deploy the floating-TTL cache, then run the production check that task 7.4 records as **not performed**:
  two identical `/sensor-data` polls inside one entry's validity must give `X-Cache: MISS` then `HIT`, tile serving must be
  unaffected, and upstream call volume must drop for cached sensors. The change is already archived with that gap stated in
  `openspec/changes/archive/2026-11-18-add-sensor-data-floating-ttl-cache/tasks.md`; if the check fails, open a fix change
  rather than editing the archive.
- [ ] Separate from the cache work: two tiles tests assert composite optimization is disabled by default while
  `application.yml` has carried `osm-tiles.optimization.enabled: true` since the archived optimization change —
  `OsmTilesConfigurationTest.defaultsMatchApplicationYml` and `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`.
  Decide whether the config or the assertions are wrong, in its own change.

## Active Decisions and Considerations
- Micronaut 5 chosen for latest framework improvements and long-term support
- Stateless architecture maintained - no database; the only in-process state is bounded Ehcache: tiles and composites on heap+disk, `/sensor-data` responses heap-only with no disk tier, so a restart never replays an old reading
- Virtual threads (Java 25) used for I/O-bound request handling
- Vavr used for functional error handling patterns
- External configuration loaded from GitHub raw content
- JUnit Platform Launcher added for proper test discovery and execution

## Important Patterns and Preferences
- Plugin-based provider system (`WindDataProvider` interface with implementations)
- Abstract base classes for similar providers (`BaseWindyDataProvider`)
- Global exception handling via dedicated `@Controller` bean (`GlobalExceptionHandler`)
- Configuration externalized via `application.yml` with environment variable overrides

## Learnings and Project Insights
- Micronaut 5 migration was straightforward due to clean architecture
- Test infrastructure (testcontainers, MockServer, JUnit Platform Launcher) fully prepared but tests not yet written
- OpenAPI integration working but endpoint annotations still needed
- MockServer client downgraded from 6.1.0 to 5.15.0 for compatibility
- JUnit Platform Launcher required as testRuntimeOnly for proper test execution in modern Gradle setups

- Production host `vm67706.vpsone.xyz` / `89.46.42.210`: **port 3084 is sshd**, the app listens on **443** (HTTPS, keystore
  mounted at `/home/keystore.p12`). Probing `http://89.46.42.210:3084` "returns nothing" because that is not the app port.
- 1chip.ru serves its site root (36 KB) to that host but answers **`200` with `content-length: 0`** for every
  `windt.php?id=…` from it — in every header, cookie, HTTP-version and plain-HTTP variant tried — while the identical URL
  returns ~34 KB from a residential workstation. Windguru's `iapi.php` answers `403 forbidden` from the same host, whereas
  `windy` works there normally. Both non-Windy sources are unusable from that server's egress.
