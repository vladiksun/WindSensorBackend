## Context

See proposal.md for motivation. Current state: a plugin-based provider architecture where `WindDataProvider<T>` is the contract, `BaseWindyDataProvider<T>` (abstract) supplies the generic timed-readings reduction (`buildTimedReadings`, `getReadingsByInterval`, `reduceWindowReadings`) and holds `name`/`url`/`objectMapper`. Providers are Micronaut `@Singleton` beans collected into a name-keyed map in `ProxyService`; each is configured by a `WindDataProviderConfig` produced via `@EachProperty("wind-providers")` (fields: `name`, `url`). Existing providers (Windy, Neduet) parse JSON with Jackson serde. There is no HTML parser in the stack today.

1chip.ru exposes data only via a server-side-rendered HTML page at `/windt.php?id=<id>`. From a live capture of `https://1chip.ru/windt.php?id=0002`:

- A header table shows the current reading; the **past-data** table lives inside `<div id="data_table">` and lists rows **newest-first**.
- Each past-data row's `<td>` cells map to fixed indices: `[0]` = `hh:mm`, `[1]` = arrow, `[2]` = min (inside `<small>`), `[3]` = avg, `[4]` = gust/max, `[5]` = empty, `[6]` = compass text (e.g. `NNW`), `[7]` = degrees (e.g. `360°`), `[8]` = temperature, `[9]` = trailing empty cell. Note the header uses two `colspan="2"` cells, so data rows have 10 physical cells vs 9 logical columns — index mapping must follow the verified layout.
- The calendar date appears on the "Arrived" line as `dd/MM/yyyy HH:mm:ss` (e.g. `06/09/2026 23:06:03`).

## Goals / Non-Goals

**Goals:**
- Parse 1chip.ru HTML into `SensorDataDTO` using the existing provider abstraction and reduction logic.
- Keep surface area minimal: reuse `BaseWindyDataProvider` and `ProxyService` unchanged.
- Fail soft: never crash a request because of an unexpected page shape.

**Non-Goals:**
- No changes to the public API, controller, or other providers.
- No caching or rate limiting (matches the current stateless design).
- Not extracting temperature, signal level, or voltage (not part of `SensorDataDTO`).

## Decisions

1. **Extend `BaseWindyDataProvider<OneChipMeasurement>`** rather than implement `WindDataProvider` directly.
   - Rationale: reuses the generic windowing/reduction logic verbatim; only URL building and parsing differ. Alternative — a standalone implementation — would duplicate the reduction logic. Rejected.

2. **Use Jsoup for HTML parsing.**
   - Rationale: de-facto standard, lenient toward malformed markup, small footprint, trivially unit-testable against fixtures. Alternatives: regex (fragile to any markup change) rejected; JDK DOM (needs XML well-formedness) unsuitable for this loose HTML.

3. **New plain record `OneChipMeasurement(float min, float avg, float gust, float dir, long timestamp)`** in `com.vb.wingfoil.response.onechip`.
   - Rationale: internal model built manually from HTML, so it does not need Jackson `@Serdeable` (unlike the Windy/Neduet records that deserialize JSON).

4. **Class name `OneChipDataProvider`, `NAME = "onechip"`, config key `onechip`.**
   - Rationale: Java identifiers cannot begin with a digit, so the requested `1ChipDataProvider` is invalid. The externally visible provider name is `onechip`, consistent with the class name.

5. **Parsing strategy:** select the `#data_table` table rows, skip the header row (first cell text == `hh:mm`), read values by the fixed cell indices above, strip the degree symbol from `[7]`, and parse floats. **Reverse the resulting list to ascending (oldest→newest)** before delegating, because `getReadingsByInterval`/`getLastReading` treat the last element as the most recent.

6. **Timestamps:** parse the "Arrived" date (`dd/MM/yyyy`) as the reference date and combine it with each row's `hh:mm` to produce **epoch seconds** (the unit used by the other providers and required by the shared `getReadingsByInterval` windowing, which compares a millisecond-free second-difference against `readingWindowSeconds`) using the system-default zone. Fall back to the current date when "Arrived" is absent.

7. **Error handling:** wrap parsing in Vavr `Try`; on any failure recover to `List.of(SensorDataDTO.empty())` and log (mirrors `WindyDataProvider`'s `.recover`). Skip individually malformed rows instead of aborting the whole page.

8. **Dependency added inline in `build.gradle`** (`implementation("org.jsoup:jsoup:<version>")`), matching the existing inline-coordinate convention (the project has no Gradle version catalog).

## Risks / Trade-offs

- **[Fragile to markup changes]** 1chip.ru may alter its HTML → Mitigation: centralize selectors/index mapping in one method; unit test against a captured fixture; fail soft to an empty result so a layout change degrades gracefully instead of erroring every request.
- **[Timezone offset]** "Arrived"/row times are sensor-local (Egypt, UTC+2); converting with the system-default zone can shift absolute timestamps by a fixed offset if the server TZ differs → Mitigation: relative ordering and windowing are unaffected; document the limitation; make the zone configurable later if absolute-time accuracy matters.
- **[Column-index coupling]** Data rows have 10 physical cells vs 9 logical columns due to `colspan` headers → Mitigation: map by the verified fixed indices from the live capture and assert the expected cell count in the test.
- **[New dependency size]** Jsoup adds a few hundred KB → acceptable for correctness; single well-known library.
- **[ACCEPT header]** `ProxyService` sends `Accept: application/json`; 1chip ignores it and returns HTML → no change needed (verified against the live endpoint).

## Migration Plan

Additive only. Deploy, then enable per-sensor by adding `provider: onechip` and the 1chip.ru `id` to spots.json entries. Rollback: remove the `onechip` config key and revert the code; no data migration required.

## Open Questions

- **Absolute-timestamp timezone:** currently assumed system-default zone. If production requires exact wall-clock alignment with the sensor's local time, a per-provider `timezone` config option can be added later without changing the approach or task breakdown.
