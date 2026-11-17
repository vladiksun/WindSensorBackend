# Tasks

## 1. Reproduce the real page contract in tests

- [x] 1.1 Add captured live fixtures under `src/test/resources/onechip/` — `windt-0264.html` and `windt-0001.html` (full pages fetched from 1chip.ru) and `windt-empty-shell.html` containing exactly `</div></div>` — and verify they load through `OneChipDataProviderTest.class.getResourceAsStream(...)`.
- [x] 1.2 Add a `windt-group-only.html` fixture (recent table header with no data rows, grouped table populated) and a `windt-renamed-captions.html` fixture derived from the `0264` capture with the speed captions rewritten; verify both parse to zero measurements with the **current** provider code, proving today's implementation cannot tell these cases apart. Verified before implementation: the current code read 5 values out of the renamed table (wrong columns) and returned no data for the grouped-only page.
- [x] 1.3 Add `parsesCapturedLivePageIntoRealReadings` against `windt-0264.html`, pinning "now" through the existing `currentTimeSeconds()` seam, asserting non-zero min/avg/gust/direction, ascending order and a newest reading inside the requested window; verify with `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.OneChipDataProviderTest'`.

## 2. Request identity for an HTML upstream

- [x] 2.1 Implement `OneChipDataProvider.requestHeaders(sensorId)` returning a browser-like `User-Agent`, `Accept: text/html,application/xhtml+xml;q=0.9,*/*;q=0.8` and `Accept-Language: en`; verify by asserting the returned map in `OneChipDataProviderTest`.
- [x] 2.2 Change `ProxyService` so provider-declared headers replace (`setHeader`) rather than append to (`addHeader`) its defaults, keeping `Accept: application/json` for providers that declare nothing; verified with `WindSensorControllerMockServerTest` scenarios asserting the upstream request carries the HTML `Accept` for a 1chip sensor (and no `application/json` alongside it) and unchanged headers for a windy sensor.

## 3. Header-driven column layout and table selection

- [x] 3.1 Replace the fixed `CELL_*` / `MIN_CELLS_FOR_DATA_ROW` constants with a column layout resolved from a candidate table's header row, expanding `colspan`, matching `hh:mm`/`min`/`Avg`/`Gust`/`Dir` case-insensitively, and rejecting tables whose header cannot resolve time, speed and numeric-direction columns; verified with tests over `windt-0264.html` (value fidelity for a known row) and `windt-renamed-captions.html` (zero measurements, `NO_DATA_TABLE`).
- [x] 3.2 Read data rows through the resolved layout so extra cells (compass name, signal level, temperature, trailing empties) are ignored and short rows are skipped; verified with tests over the live fixtures plus a synthetic body mixing 9-cell and 10-cell rows and one truncated row, asserting every valid row survives with unshifted values.
- [x] 3.3 Select the recent table (`#data_table`) first and fall back to the grouped table (`#group_table`) when it yields no usable rows; verified with `windt-group-only.html` returning its grouped readings while the other suites stay green.

## 4. Extend into grouped history when the window demands it

- [x] 4.1 When the recent table's span is narrower than `readingWindowSeconds`, merge grouped rows into the series, deduplicate by reconstructed timestamp preferring the finer-grained recent row, and sort ascending before windowing; verified with a 14 400 s request against `windt-0264.html` (recent table spans ~3 h), asserting the series reaches 16:30, contains no duplicate timestamps, and keeps the recent 17:30 row over the grouped one.
- [x] 4.2 Confirm the default 3600 s path is unchanged for live pages; verified by `keepsUsingOnlyTheRecentTableWhenItAlreadyCoversTheWindow` (51 recent rows, source `RECENT`) and the unchanged five-reading assertion in `parsesCapturedLivePageIntoRealReadings`.

## 5. Name every unproductive response

- [x] 5.1 Make the parse stage return measurements plus a reason (`NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR`) and counters (tables seen, candidate rows, valid rows), exposed through a package-private seam so tests can assert them — `OneChipDataProvider.ParseOutcome` / `parseMeasurements(body, window)`.
- [x] 5.2 Log exactly one WARN per unproductive response carrying the reason code, URL, body length and row counters — never the full body; verified by asserting the reported counters for `windt-empty-shell.html` (12 bytes, no tables) versus `windt-renamed-captions.html` (full length, three tables seen, layout unresolved).
- [x] 5.3 Keep the response contract: every reason still yields `Try.success(List.of(SensorDataDTO.empty()))`, while a non-2xx upstream still fails as an upstream error; verified with `OneChipDataProviderTest` (zero-reading shape for each reason, zero reading never mixed with real ones) plus `onechipUpstreamFailureStaysAnUpstreamError` where 1chip.ru answers `503`.

## 6. Bound the timestamp correction

- [x] 6.1 Implement the three-band correction — no correction within tolerance; clamp readings newer than "now" down to "now" with a WARN when the overshoot is below half a day; whole-day roll-back of `ceil((overshoot − tolerance) / 86_400)` days at or above half a day — keeping the `currentTimeSeconds()` seam; verified with tests pinning "now" across all three bands, including a 66-second-skew case that must **not** move fresh data about a day into the past (the old code shifted that series by a full day), plus the existing `neverReturnsAFutureReading` one-day-shift expectation and a two-day roll-back.

## 7. Bound upstream call duration

- [x] 7.1 Build the shared `CloseableHttpClient` with explicit connect (10 s), response (30 s) and connection-lease (10 s) timeouts from `wind-sensor.upstream-*`, and disable HttpClient's automatic retries so one client request issues exactly one upstream call; verified by `WindSensorControllerUpstreamTimeoutTest`, whose upstream is a local socket that accepts and stays silent, asserting `/sensor-data` fails as an upstream error in seconds (measured 2.7 s with a 2 s response timeout) rather than hanging, and by `onechipUpstreamFailureStaysAnUpstreamError` asserting exactly one upstream request for a `503`. MockServer 5.15 exposes no response-delay option, so the stalled case is exercised with a raw socket instead of a delayed MockServer expectation.
- [x] 7.2 Confirm the rebuilt client still speaks HTTPS and reads the real page: a temporary probe fetched `windt.php?id=0264`, `id=0001` and an unused id through `ProxyService.buildHttpClient(...)`, answering `200 text/html; charset=UTF-8` at 31 717 / 33 935 / 12 bytes respectively (the probe was deleted after recording this).

## 8. Integration checks

- [x] 8.1 Run the provider and controller suites green: `./gradlew test --tests 'com.vb.wingfoil.provider.impl.*' --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest' --tests 'com.vb.wingfoil.WindSensorControllerUpstreamTimeoutTest'` — 58 tests, 0 failures.
- [x] 8.2 Compile both source sets and pass formatting: `./gradlew -q :compileJava`, `./gradlew -q :compileTestJava`, `./gradlew -q spotlessCheck` (after `spotlessApply`).
- [x] 8.3 Whole-suite run (`./gradlew test`) leaves two failures in `com.vb.wingfoil.tiles` (`OsmTilesConfigurationTest.defaultsMatchApplicationYml`, `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`). Confirmed pre-existing: with this change's files stashed, both fail identically on unmodified `master`. Out of scope for this change.

## 9. Production verification (server-side, not code)

- [ ] 9.1 Record which commit the running image was built from by comparing production behavior with local HEAD — today `provider: "windguru"` fails on production exactly like an unknown provider, while `WindguruDataProvider` exists in HEAD and its tests pass; verify by capturing both responses in this change's notes.
- [ ] 9.2 From the production host, fetch `https://1chip.ru/windt.php?id=0264` with a browser-like user agent and record the HTTP status and byte length; verify that the recorded result distinguishes "full page (~30 KB)" from "empty shell (12 bytes)" or a connection failure. Reference measurement from a workstation: `200`, 31 717 bytes for `0264` and 33 935 for `0001`, while an unused id answers `200` with the 12-byte shell — so a 12-byte answer from the server means "nothing to render", not a blocked request.
- [ ] 9.3 Dispatch `Deploy to Server`, then re-run `POST /sensor-data` for `0264` and `0001` against production and read the container log; verify that either non-zero readings are returned or the log names a reason code, and record which occurred.
- [ ] 9.4 If 9.2/9.3 show refusal from the server while the same URL works from a workstation, propose the `vladiksun/WindSensorConfig` change routing Dahab – Assalah to Windguru station `15693`; verify by showing the resulting sensor entry and its live `/sensor-data` response.

### Note on the 2026-11-17 attempt at group 9 from a workstation

The production host did not answer from this workstation when these tasks were attempted: `GET https://vm67706.vpsone.xyz/spots-data` and `POST /sensor-data` (windguru, `15693`) both timed out client-side at 15 s and 20 s respectively, so no production response could be recorded — neither the pre-deploy baseline for 9.1 nor the post-deploy check for 9.3. Groups 9.1–9.4 have to be run from the server (or from a workstation that can reach it) after the `Deploy to Server` dispatch.

## Workflow follow-up

- [x] Start the apply workflow for this change before writing any implementation; groups 2–7 were implemented only after the artifacts were approved.
- [ ] After implementation passes its checks, archive the change so `openspec/specs/onechip-wind-provider/spec.md` is created — the two archived onechip deltas were never synced into `openspec/specs/`.
- [ ] Update `memory-bank/activeContext.md` and `memory-bank/progress.md` with the group 9 outcome, because it decides whether this bug is fixable in code at all or is an egress/config problem.
