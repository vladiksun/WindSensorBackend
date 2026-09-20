## Context

`OneChipDataProvider extends BaseWindyDataProvider<OneChipMeasurement>` fetches `https://1chip.ru/windt.php?id=<id>` and parses the past-data table (`#data_table`) with Jsoup. Rows are listed **newest-first**; each data row carries only an `hh:mm` time (cell `[0]`), min/avg/gust/direction, and no date. The only calendar date on the page is the "Arrived" line (`dd/MM/yyyy HH:mm:ss`).

Current behavior (`parseRow`): every row's timestamp = `LocalDateTime.of(arrivedDate, rowTime).atZone(zone).toEpochSecond()`, i.e. one shared `arrivedDate` for all rows. This was accepted in the original change as a known limitation (see archived design "Open Questions"). It breaks when the table spans multiple days and can yield future timestamps.

Constraints:
- Timestamps must remain **epoch seconds** (the unit used by `getReadingsByInterval`, which compares a second-difference against `readingWindowSeconds`).
- The list must end up **ascending** (oldest→newest) because `getReadingsByInterval`/`getLastReading` treat the last element as the most recent.
- Zone conversion stays driven by the existing `wind-sensor.wind-providers.onechip.timezone` config (falls back to system default).
- Fail soft: never fail a request over an unexpected page shape.

## Goals / Non-Goals

**Goals:**
- Assign each row the correct calendar day so multi-day tables produce accurate, monotonic timestamps.
- Ensure no returned reading has a timestamp later than the current time.
- Keep single-day and empty/malformed handling byte-for-byte compatible with today's tests.

**Non-Goals:**
- No changes to the public API, controller, other providers, or configuration keys.
- No attempt to infer exact sub-minute timing or per-row dates beyond what the page provides.
- No caching.

## Decisions

### 1. Per-row date reconstruction anchored at the newest row

Parse rows in their native **newest-first** order. Let `anchorDate` = the parsed "Arrived" date (fallback: today). Assign the newest row `anchorDate`. For each subsequent (older) row, start from the previous row's date and compute candidate = `prevDate @ rowTime`; while the candidate is **not strictly before** the previous row's datetime, decrement the date by one day and recompute. Convert the final `LocalDateTime` to epoch seconds in the configured zone.

- Rationale: within a single day the wall-clock times strictly decrease going back, so no day is subtracted; when the sequence crosses midnight the wall-clock time *increases* (e.g. `00:30` → `23:30`), which triggers exactly one day rollback. This correctly maps a table that spans into the previous day(s).
- Alternative rejected: keep one shared date (current bug); guess day boundaries from fixed intervals (fragile to sparse/irregular data).

### 2. Future-timestamp guard (whole-series day shift)

After reconstructing all timestamps, let `maxTs` be the largest and `now = currentTimeSeconds()`. If `maxTs - now > FUTURE_TOLERANCE_SECONDS`, shift **every** timestamp back by `daysBack = max(1, (maxTs - now) / 86400)` whole days.

- Rationale: a reading cannot be from the future. When the "Arrived" date places the whole series ahead of now (e.g. Arrived date is today but the sensor's latest data is actually from yesterday evening), shifting the entire series back by whole days preserves internal spacing and ordering while removing the future values.
- `FUTURE_TOLERANCE_SECONDS` (constant, e.g. `3600`) absorbs minor clock skew / rounding so a legitimately-current reading a few minutes ahead is not over-corrected by a full day.
- Applied during parsing (on `OneChipMeasurement.timestamp`) **before** delegating to `buildTimedReadings`, so both the windowed path and the `window==0 && count==0` "latest only" path benefit.
- Alternative rejected: clamp individual readings to `now` (distorts spacing/ordering); drop future readings (loses data).

### 3. Testable "current time" seam

Add a package-private seam, e.g. `long currentTimeSeconds()` returning `Instant.now().getEpochSecond()`, used only by the future-guard. Unit tests subclass the provider (or use a test-only constructor) to pin this value, keeping the future-guard deterministic without injecting a `Clock` bean. The public constructor signature is unchanged.

- Rationale: minimal surface; matches the project's plain-unit-test style (tests already construct the provider directly with a null `ObjectMapper`).

## Risks / Trade-offs

- **[Heuristic day-shift]** The future-guard assumes a uniform whole-day offset when the newest reading is far in the future. Mitigation: bounded by the tolerance + whole-day granularity; single-day tables near "now" are unaffected (guard only fires when `maxTs - now > tolerance`).
- **[Ambiguous large gaps]** A table with an unusually large gap between consecutive rows still resolves to a valid, non-future, ascending series even if the true intermediate days are unknown — acceptable given the page exposes no dates.
- **[Existing tests]** Single-day fixture (all rows same day, well in the past) yields identical results after the change: rollover never triggers (times strictly decrease) and the guard never fires (timestamps < now). Verified against `onechip-windt.html`.

## Migration Plan

Purely internal logic change behind the existing `onechip` provider. Deploy; no config, migration, or client changes. Rollback: revert the code.

## Open Questions

- **Tolerance value:** `FUTURE_TOLERANCE_SECONDS = 3600` is a reasonable default for ~hourly wind readings; confirm it is acceptable, or make it configurable if production clocks are significantly skewed.
