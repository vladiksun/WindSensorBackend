# Design

## Context

See proposal.md – Why. Current state relevant to the approach:

- `WindguruDataProvider extends BaseWindyDataProvider<WindguruMeasurement>`. Its pipeline is: deserialize
  `WindguruStationData` → `toMeasurements(...)` zips the parallel arrays into `WindguruMeasurement` records
  (skipping rows with any `null`) → `buildTimedReadings(...)` either calls `getLastReading(...)` (when
  `readingWindowSeconds == 0 && numberOfReadings == 0`) or `getReadingsByInterval(...)` (window filter → thinning
  → `map` through `mapToDTO`). Both terminal paths build the DTO from the same record fields.
- Nothing in the codebase converts units today: `WindyDataProvider`, `NeduetDataProvider` and `OneChipDataProvider`
  all copy upstream numbers straight into `SensorDataDTO`, which is unit-less (`float windMax, windAvg, windMin,
  windDirection, timestamp`).
- The provider's own javadoc currently asserts "speeds already in knots … so no unit conversion … is needed", and
  `WindguruMeasurement` documents its speeds as "as published by Windguru" (knots). Both statements become wrong and
  must be updated as part of the change.

### Upstream facts re-verified live on 2026-11-04 (station 15693)

Same-origin `fetch` from the station page (so the mandatory `Referer` is present), last hour,
`avg_minutes=1`, compared with what the page renders:

| Call | Result |
| --- | --- |
| `…&q=station_data&id_station=15693&avg_minutes=1&graph_info=1&from=&to=` (baseline) | 200, `wind_avg` tail `[23.3, 23.5, 23.5, 25]`, `wind_max` tail `[27.4, 26.6, 28.9, 28.3]` |
| baseline + `unit_speed=mps` | **byte-identical** values |
| baseline + `unit_speed=m/s` | **byte-identical** values |
| baseline + `units=ms` | **byte-identical** values |
| baseline + `speed_unit=ms` | **byte-identical** values |
| Station page rendering | unit selector shows `knots`; current readout **"25 knots — max: 28.3, min: 20."** |

Conclusion: the payload unit is knots, it is not negotiable through query parameters, and therefore the conversion
is ours to perform. The archived design for `add-windguru-wind-provider` recorded the same finding; this probe adds
the negative result for every unit parameter worth trying.

## Goals / Non-Goals

**Goals:**
- Every wind speed a Windguru request returns is in metres per second at the point it leaves the provider.
- Exactly one conversion site, so no reading path can emit knots and none can convert twice.
- Converted values are deterministic and assertable to the centimetre-per-second digit (2 decimals).
- Units stated in code where a maintainer will hit them: `WindguruMeasurement` fields and the provider javadoc.

**Non-goals (design-level):**
- No unit abstraction (`Unit` enum, per-provider `speedUnit` config, shared converter utility) for one provider's
  need — a knob with two values and no caller asking for it.
- No change to `SensorDataDTO`, `BaseWindyDataProvider`, `ProxyService`, the Windguru URL template or the
  `Referer` hook: this stays inside the Windguru provider so no other provider's output moves.
- No re-capture of the test fixture; it remains a faithful knot-shaped upstream capture.

## Decisions

### 1. Convert while unzipping the columns (`toMeasurements`), not at `mapToDTO`

`WindguruMeasurement` becomes an SI record: `min`, `avg`, `gust` are metres per second; `dir` stays degrees and
`timestamp` stays epoch seconds. The conversion is applied where the wire values enter the domain, so windowing,
thinning, `getLastReading` and `mapToDTO` all operate on one unit and cannot disagree.

- *Alternative — convert in `mapToDTO`*: one fewer edit and keeps `WindguruMeasurement` byte-faithful to upstream.
  Rejected: the record is not merely a wire DTO here — it is the input to windowing and reduction, so any future
  speed-based logic on measurements (gust thresholds, sanity filters, debug output) would silently run on knots
  while everything above it is m/s.
- *Alternative — convert in `BaseWindyDataProvider`/`SensorDataDTO`*: rejected outright; shared code must stay
  unit-agnostic because each provider publishes a different upstream unit.

### 2. Factor as the exact rational `1_852d / 3_600d`, computed in `double`

One knot is one international nautical mile (exactly 1852 m) per hour (exactly 3600 s), so
`private static final double METRES_PER_SECOND_PER_KNOT = 1_852d / 3_600d;` (= 0.5144444…) is exact by definition
and needs no comment to justify its digits. Arithmetic stays in `double` and narrows to `float` once, at the end.

- *Alternative — literal `0.514444f`*: agrees to ~4·10⁻⁷ m/s but invites "why that many digits?" and does the
  multiplication in `float`, where 23.1 kn lands slightly differently. Rejected for clarity, not accuracy.
- *Alternative — `knots * 1.852 / 3.6` (km/h ÷ 3.6)*: identical result, two steps and an extra rounding point.

### 3. Report rounded half-up to 2 decimals, through one package-private helper

```java
static float knotsToMetresPerSecond(float knots) {
    return (float) (Math.round(knots * METRES_PER_SECOND_PER_KNOT * 100d) / 100d);
}
```

`Math.round` is monotonic non-decreasing, so a published `min ≤ avg ≤ max` stays ordered after conversion (ties are
possible in calm air and are harmless). Two decimals match the resolution Windguru actually publishes: its values
carry 0.1 knot ≈ 0.0514 m/s, which two decimals represent without inventing precision.

- *Alternative — raw `float` result*: lossless but serializes as `11.88365707397461`, noise for a watch client, and
  it makes assertions depend on float representation rather than behaviour. Rejected.
- *Alternative — 1 decimal*: tidier display, but it collapses distinct published readings (23.1 kn → 11.9 and
  23.2 kn → 11.9) and discards half the upstream resolution. Rejected.
- Keeping the helper `static` and package-private lets the factor and rounding be unit-tested directly without going
  through a payload; provider tests then assert end-to-end values only.

### 4. Fixture stays knots; assertions carry the converted numbers

`src/test/resources/windguru/station-data.json` and the inline MockServer payload are captures of what upstream
sends, so they keep their published knot values (23.1, 26.0, 20.2 …) and only the expectations change
(11.88, 13.38, 10.39). A pre-converted fixture would hide the very fact this change is about and would no longer be
replayable against a live capture.

Reference conversions for the assertions (factor 0.5144444…, rounded to 2 decimals):

| Fixture row | published `wind_min` / `wind_avg` / `wind_max` (kn) | expected m/s |
| --- | --- | --- |
| oldest (`1793785260`) | 20.2 / 23.1 / 26.0 | 10.39 / 11.88 / 13.38 |
| newest (`1793787360`, the `latestOnly` case) | 20.8 / 24.3 / 28.7 | 10.70 / 12.50 / 14.76 |

### 5. No config toggle, no upstream unit request

The live probe shows Windguru cannot be asked for metric speeds. A `windguru.speed-unit` key would add a second
behaviour path that upstream cannot satisfy and that no client has requested: the unit of a reading is decided by the
response contract, not by per-provider config. If units ever become selectable, that belongs to `/sensor-data` for
all providers at once (see Open Questions).

## Risks / Trade-offs

- [`SensorDataDTO` becomes provider-dependent in unit: Windguru now m/s while windy/neduet/onechip publish whatever
  their upstream sends] → Scope is deliberately confined to the provider the user named; the provider javadoc and
  `WindguruMeasurement` state the unit explicitly, and auditing the other providers (or declaring the unit in the
  response) is raised as an Open Question instead of being silently bundled here.
- [Existing clients of `"provider": "windguru"` see ~48.6 % smaller numbers] → Deploy with a note to whoever owns the
  external spots config, verify one spot against the station page before pointing others at `windguru`; rollback is
  reverting one commit (no persisted state, no schema change).
- [A later change converts again downstream, or converts in `mapToDTO` while measurements still carry knots] → Single
  helper applied only in `toMeasurements`; units written on the record's fields; a test compares the latest-only
  reading with the same reading inside a windowed series, so a second application would show as 6.43 instead of 12.50.
- [Rounding discards sub-cm/s differences and can make `windMin == windAvg` in calm air] → Accepted: ordering is
  preserved and upstream resolution (0.1 kn) is coarser than what is discarded.
- [If Windguru one day publishes this endpoint in m/s, the provider would divide by 1.94 again, silently] → The
  provider javadoc names the expected upstream unit and the assertions encode it, so a live comparison against the
  station page exposes the drift immediately.
- [Two overlapping "normalization" requirements once this delta is synced (archived spec was never synced)] → Noted
  in proposal.md; reconcile at archive time rather than duplicating behavior across two requirement blocks.

## Migration Plan

1. Land provider + test changes in one commit (no config key, no dependency, no data migration).
2. Deploy, then call `/sensor-data` for station `15693` and compare with the station page: published 23–25 kn should
   read back as ≈ 11.9–12.9 m/s average with gusts ≈ 14.8 m/s.
3. Tell the consumer(s) and the external spots-config owner that Windguru speeds are now metres per second before
   pointing more spots at `windguru`; keep Dahab – Assalah as the single comparison spot until confirmed.
4. Rollback = revert the commit; responses return to knot-valued numbers and nothing else changes.

## Open Questions

- Should `/sensor-data` state its unit explicitly (a `unit` field, or "m/s" documented in OpenAPI) so a client mixing
  `windguru` with other providers cannot guess wrong? That touches every provider and the client contract, so it is a
  separate change.
- Which units do `windy`, `neduet` and `onechip` actually publish, and should they be normalized to m/s in a
  follow-up so the whole API agrees (memory-bank's productContext still describes responses as knots)?
- Should `add-windguru-wind-provider`'s archived spec be synced into `openspec/specs/` before this change is archived,
  and if so should the two normalization requirement blocks be merged into one?


