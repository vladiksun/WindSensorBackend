# Proposal

## Why

Windguru publishes its station readings in **knots** and `WindguruDataProvider` copies those numbers
verbatim into `SensorDataDTO`, so `/sensor-data` for a `windguru` sensor (station 15693, Dahab – Assalah)
reports an average of ~23 when the real wind is ~12 m/s — every value is wrong by a factor of 1.9438 for
any consumer that reads the API in metres per second.

Re-verified live on 2026-11-04 against `https://www.windguru.cz/station/15693`: the `station_data` payload
(`wind_avg` 23.3–25.0, `wind_max` 26.6–28.9) matches exactly what the station page labels **"25 knots,
max: 28.3, min: 20."**, and adding `unit_speed=mps`, `unit_speed=m/s`, `units=ms` or `speed_unit=ms` to the
call changes **nothing** in the response. Windguru offers no metric variant of this endpoint, so the
conversion has to happen on our side.

## What Changes

- `WindguruDataProvider` converts each published `wind_min`, `wind_avg` and `wind_max` from knots to
  metres per second (1 knot = 1852/3600 m/s = 0.514444… m/s) before the value can reach `SensorDataDTO`;
  wind direction (degrees) and epoch-second timestamps are left untouched.
- Conversion happens **once**, while the columnar arrays are unzipped, so both reading paths
  (`mapToDTO` for windowed series and `getLastReading` for latest-only requests) see only converted values
  and neither can emit raw knots or convert the same number twice.
- `WindguruMeasurement` changes meaning: its three speed fields become metres per second instead of
  "as published (knots)".
- Converted speeds are reported to **2 decimal places** (23.1 kn → `11.88`), which keeps the JSON readable
  and matches Windguru's own 0.1-knot publishing resolution.
- **BREAKING** for consumers of `/sensor-data` with `"provider": "windguru"`: numeric wind values drop by
  ~48.6 % (23.1 → 11.88). Response shape, field names, timestamps and directions are unchanged; no new or
  removed fields.
- Tests move from "values carried through unchanged" to converted expectations, plus new cases pinning the
  factor, the rounding, the untouched direction/timestamp, and the single-conversion guarantee:
  `WindguruDataProviderTest` and the `windguruProviderReturnsNormalizedReadings` contract case in
  `WindSensorControllerMockServerTest`.

**Non-goals:** no unit change for `windy`, `neduet` or `onechip` (they keep passing upstream values through);
no configurable unit toggle; no change to the `SensorDataDTO` shape, to `/spots-data`, or to the Windguru URL/`Referer`
behaviour established by `add-windguru-wind-provider`.

## Capabilities

### New Capabilities

- `windguru-wind-provider`: Windguru station readings normalized into the backend's timed-reading contract —
  positional unzipping of the columnar arrays, speeds expressed in metres per second, direction and epoch
  timestamps preserved. Two requirements are declared: the normalization requirement (restated with the unit
  fix) and a new "Wind speed unit conversion" requirement pinning factor, precision and what must not change.

### Modified Capabilities

_None registered_ — `openspec list --specs` returns an empty inventory because `add-windguru-wind-provider` was
archived **without** syncing its spec, so `openspec/specs/windguru-wind-provider/spec.md` does not exist and
`openspec validate --strict` rejects MODIFIED against a missing base ("only ADDED requirements are allowed for
new specs"). The delta therefore reuses the established capability path and declares it as a new spec whose text
supersedes the archived wording:

| Archived wording (`archive/2026-11-04-add-windguru-wind-provider`) | New wording in this delta |
| --- | --- |
| "…wind speed **in knots** plus wind direction in degrees, **without unit conversion**" | "…wind speed **in metres per second** plus wind direction in degrees" |
| Scenario "Published speeds reported unchanged": 23.3 knots → `23.3` | Scenario "Published knots reported as metres per second": 23.1 knots → `11.88` |

If the archived provider spec is ever synced into `openspec/specs/`, the normalization requirement should be
reconciled so this capability keeps one coherent requirement rather than two overlapping ones (follow-up noted in
tasks.md).


## Impact

- **Code**: `src/main/java/com/vb/wingfoil/provider/impl/WindguruDataProvider.java` (conversion constant,
  helper, `toMeasurements`, class javadoc) and `src/main/java/com/vb/wingfoil/response/windguru/WindguruMeasurement.java`
  (field semantics/javadoc only). No shared code (`BaseWindyDataProvider`, `ProxyService`, `SensorDataDTO`)
  is touched, so no other provider's output changes.
- **Tests**: `src/test/java/com/vb/wingfoil/provider/impl/WindguruDataProviderTest.java` and the windguru
  block of `src/test/java/com/vb/wingfoil/WindSensorControllerMockServerTest.java`. The captured fixture
  `src/test/resources/windguru/station-data.json` stays as published (knots) — it is upstream data, and the
  assertions change instead.
- **Consumers / rollout**: any client that already reads Dahab – Assalah from `windguru` must expect smaller
  numbers; the spot entry lives in the external `WindSensorConfig` repo, so the unit change has to be
  announced with the deploy. Rolling back the code restores knots byte-for-byte (no persisted state).
- **Dependencies / config**: none — plain arithmetic on already-deserialized values, no new library, no
  `application.yml` key.
