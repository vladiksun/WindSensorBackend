## 1. Timestamp reconstruction (per-row dates)

- [x] 1.1 In `OneChipDataProvider.parseMeasurements`, stop passing a single shared date to every row. Parse rows in their native newest-first order, keeping each row's `LocalTime` alongside its values.
- [x] 1.2 Anchor the newest row to the "Arrived" date (`extractArrivedDate`, fallback today). For each subsequent older row, start from the previous row's date; while `prevDate @ rowTime` is not strictly before the previous row's datetime, decrement the date by one day. Convert each final `LocalDateTime` to epoch seconds via `.atZone(this.zone).toEpochSecond()`.
- [x] 1.3 Keep the existing `Collections.reverse(...)` so the list ends ascending (oldest→newest) before delegating to `buildTimedReadings`.

## 2. Future-timestamp guard

- [x] 2.1 Add a package-private seam `long currentTimeSeconds()` returning `Instant.now().getEpochSecond()`.
- [x] 2.2 Add constant `FUTURE_TOLERANCE_SECONDS = 3600`. After reconstructing all timestamps, if `maxTs - now > FUTURE_TOLERANCE_SECONDS`, shift every `OneChipMeasurement.timestamp` back by `daysBack = max(1, (maxTs - now) / 86400)` whole days. Apply this before delegating to `buildTimedReadings` so both the windowed path and the latest-only path are covered.

## 3. Testing

- [x] 3.1 Extend `OneChipDataProviderTest`: add a multi-day fixture/assertion where newest-first wall-clock times increase across midnight (e.g. `00:30`, then `23:30`), asserting the older row is dated on the previous day and the series stays ascending.
- [x] 3.2 Add a future-guard test that pins `currentTimeSeconds()` (via a test subclass or test-only constructor) to a value earlier than the reconstructed newest reading, and asserts no returned timestamp exceeds the pinned "now".
- [x] 3.3 Confirm the existing single-day tests (`parsesRowsIntoNormalizedReadingsInAscendingOrder`, `returnsOnlyLatestReadingWhenNoWindowRequested`, malformed/blank cases) still pass unchanged.

## 4. Verification

- [x] 4.1 Run `./gradlew -q spotlessCheck` (run `spotlessApply` first if it fails).
- [x] 4.2 Compile: `./gradlew -q :compileJava` and `./gradlew -q :compileTestJava`.
- [x] 4.3 Run targeted tests: `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.OneChipDataProviderTest'`.
