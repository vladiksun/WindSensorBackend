# Tasks

Reference: the live re-probe on 2026-11-04 (design.md – Upstream facts) confirms `station_data` publishes knots,
that no unit query parameter changes the payload, and that the station page renders the same numbers as
"25 knots — max: 28.3, min: 20.". Per `.clinerules/coding.md`: Gradle through the wrapper, `-q` for build/spotless
only, **no** `-q` for tests. `WindSensorControllerMockServerTest` is `@Testcontainers(disabledWithoutDocker = true)`,
so its cases need Docker and skip silently without it.

Conversion targets used below (factor 1852/3600 = 0.5144444…, half-up to 2 decimals):
20.2→10.39 · 23.1→11.88 · 26.0→13.38 · 20.8→10.70 · 24.3→12.50 · 28.7→14.76 · 24.8→12.76 · 28.3→14.56.

## 1. Conversion in the provider

- [x] 1.1 In `src/main/java/com/vb/wingfoil/provider/impl/WindguruDataProvider.java`, add
      `private static final double METRES_PER_SECOND_PER_KNOT = 1_852d / 3_600d;` and a package-private
      `static float knotsToMetresPerSecond(float knots)` returning
      `(float) (Math.round(knots * METRES_PER_SECOND_PER_KNOT * 100d) / 100d)`, with javadoc naming the exact factor, the
      2-decimal half-up rounding, and that direction/timestamps are never passed through it.
      *Verify:* `./gradlew -q compileJava`; `./gradlew -q spotlessCheck` (run `-q spotlessApply` if it fails).

- [x] 1.2 Apply the helper in `toMeasurements(...)` to the three speed columns only — convert `avg`, `gust` and `min`
      when building `new WindguruMeasurement(min, avg, gust, direction, timestamp)` — leaving `direction` and `timestamp`
      exactly as published, and keeping the existing null-gap skip and `valueAt(...)` short-column handling before
      conversion. No other call site may convert.
      *Verify:* `./gradlew -q compileJava`; the tests in 2.1/2.2 pass.

- [x] 1.3 Fix the unit statements that are now false: the class javadoc of `WindguruDataProvider` (currently "speeds
      already in knots … so no unit conversion … is needed" → published in knots, converted to m/s once while unzipping)
      and the `@param min/avg/gust` lines of
      `src/main/java/com/vb/wingfoil/response/windguru/WindguruMeasurement.java` (knots → metres per second, converted from
      Windguru's knots).
      *Verify:* `./gradlew -q compileJava`; grep shows no remaining "no unit conversion" claim in the windguru sources.

- [x] 1.4 Add direct tests for the helper in
      `src/test/java/com/vb/wingfoil/provider/impl/WindguruDataProviderTest.java`: factor exactness (1 knot → 0.51),
      rounding half-up to two decimals (26.0 kn → 13.38, 24.3 kn → 12.50), `0f` stays `0f`, and ordering preserved for a
      min/avg/max triple (assert converted min ≤ avg ≤ max).
      *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest'`.

## 2. Provider tests re-expressed in metres per second

- [x] 2.1 Replace `mapsPublishedFieldsWithoutConversion` with a converted-values case (e.g.
      `convertsPublishedKnotsToMetresPerSecond`) asserting the oldest fixture row: timestamp `1793785260`,
      `windMin 10.39f`, `windAvg 11.88f`, `windMax 13.38f`, `windDirection 10.0f` (delta `0.001f`) — knots in the payload,
      m/s out of the provider.
      *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest.convertsPublishedKnotsToMetresPerSecond'`.

- [x] 2.2 Update `latestOnlyRequestReturnsTheNewestReading` to the converted newest row: `windMin 10.70f`,
      `windAvg 12.50f`, `windMax 14.76f`, `windDirection 37.0f`, timestamp `1793787360`.
      *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest.latestOnlyRequestReturnsTheNewestReading'`.

- [x] 2.3 Add a single-conversion case comparing the latest-only reading (`readingWindow` 0, `numberOfReadings` 0) with the
      same newest reading inside a windowed series (`86400`, `100`): both must report `windAvg 12.50f` — not `6.43f`, which
      is what a second application of the factor would produce — while the windowed case keeps its ascending-order
      assertions.
      *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest'` (all cases green).

- [x] 2.4 Re-check the unchanged-behaviour cases under the new unit: `skipsRowsWithMissingValues`,
      `emptyArrayBodyReturnsSingleEmptyReading`, `blankBodyReturnsSingleEmptyReading`,
      `payloadWithoutUsableColumnsReturnsSingleEmptyReading`, `unparseablePayloadIsRecoveredAsNoData` and
      `windowedRequestHonoursReadingWindowAndCount` must still assert 7 readings, the same timestamps and
      `SensorDataDTO.empty()` zeros — no speed in them may turn into a converted non-zero.
      *Verify:* the same targeted run as 2.3 reports zero failures for those methods.

## 3. Client-visible contract test

- [x] 3.1 In `src/test/java/com/vb/wingfoil/WindSensorControllerMockServerTest.windguruProviderReturnsNormalizedReadings`,
      keep `WINDGURU_PAYLOAD` as the knot-shaped capture and change only the assertions to metres per second:
      `[0].windMin` 10.39, `[0].windAvg` 11.88, `[0].windMax` 13.38, `[0].windDirection` 10.0, timestamps unchanged, plus a
      converted assertion for the last reading `[6]` (20.8/24.3/28.7 → 10.70/12.50/14.76); correct the stale comment
      "Published knots and epoch seconds are carried through unchanged".
      *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest.windguruProviderReturnsNormalizedReadings'`
      (Docker required; without it the class is skipped, so rely on 2.x as well).

## 4. Integration checks

- [x] 4.1 Formatting and compile gates: `./gradlew -q spotlessCheck` (then `-q spotlessApply` and re-check if it fails),
      `./gradlew -q compileJava`, `./gradlew -q compileTestJava`.
      *Verify:* all exit 0; `lint_files` on `WindguruDataProvider.java` and `WindguruMeasurement.java` reports no errors.

- [x] 4.2 Full windguru test gate without `-q`: `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest'`
      then `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest'`.
      *Verify:* every windguru case passes and no pre-existing case in either class (windy, neduet, onechip, spots-data,
      header probe) changes result — proving the conversion stayed inside the Windguru provider.

- [x] 4.3 Live unit check against the station page: call the configured endpoint
      (`https://www.windguru.cz/int/iapi.php?q=station_data&id_station=15693&avg_minutes=1&graph_info=1&from=<now-24h>&to=<now>`
      with `Referer: https://www.windguru.cz/station/15693`), take the newest published average in knots, and compare it with
      what `/sensor-data` for `"provider": "windguru", "id": "15693"` returns.
      *Verify:* an upstream average of 23–25 kn reads back as ≈ 11.9–12.9 m/s (gusts ≈ 14.6–14.8), i.e. knots × 0.5144, and
      the page's own "knots" readout still matches the unconverted number.

- [x] 4.4 Spec gate: `openspec validate convert-windguru-knots-to-ms --strict`.
      *Verify:* validation reports the change valid with no issues.

- [x] 4.5 Memory-bank unit notes: record in `memory-bank/progress.md` that Windguru speeds are converted knots→m/s (factor,
      rounding, and the negative result for upstream unit parameters); adjust the windguru rows in
      `memory-bank/techContext.md` and `systemPatterns.md` to state m/s for that provider; flag in
      `memory-bank/productContext.md` that "responses in knots" no longer holds for `windguru`; add design.md's Open
      Questions to `memory-bank/activeContext.md`.
      *Verify:* grep `knots|metres per second` across those four files shows the m/s statement for windguru; stage new files
      with `git add` (no commit).

## Workflow follow-up

- Resolve before archive: whether `/sensor-data` should declare its unit explicitly, and whether `windy`/`neduet`/`onechip`
  must be audited or normalized so the API is not mixed-unit (design.md – Open Questions). Both would change specs beyond
  this change.
- Decide whether to sync `add-windguru-wind-provider`'s archived spec into `openspec/specs/` first; if it is synced, merge the
  two overlapping "Columnar payload normalized into readings" requirement blocks during archive so the capability keeps one.
- Announce the breaking numeric change (~48.6 % smaller wind values for `windguru` sensors) to the client and to the owner of
  the external `WindSensorConfig` spots file before pointing more spots at this provider; keep Dahab – Assalah as the
  comparison spot until confirmed.
