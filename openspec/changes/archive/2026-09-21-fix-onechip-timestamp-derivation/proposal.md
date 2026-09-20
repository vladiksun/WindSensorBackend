## Why

The `onechip` provider derives every past-data row's timestamp by combining a **single** calendar date — the page's "Arrived" date (`dd/MM/yyyy`) — with the row's `hh:mm` time. But the 1chip.ru past-data table lists rows **newest-first** and frequently **spans more than one calendar day**, showing only the time-of-day per row (no date). Stamping every row with one shared date misdates rows that actually belong to earlier days and can produce timestamps **in the future**.

Observed live: a `/sensor-data` call for a `onechip` sensor returned a "past" reading stamped `1790022120` (= `2026-09-21 23:22` Africa/Cairo) that is later than the current time. Consumers that rely on correct ordering or absolute time get a broken series.

## What Changes

- **Reconstruct each row's calendar date individually** instead of reusing one shared "Arrived" date: anchor the newest row to the "Arrived" date and roll older rows back across midnight boundaries, so a multi-day table maps onto the correct dates.
- **Guarantee no returned reading is dated in the future:** when the reconstructed newest timestamp falls after the current time (beyond a small tolerance), shift the whole series back by whole days so the newest reading is at or before now.
- Preserve existing behavior for single-day tables and for blank/malformed responses (unchanged).
- Add a small, testable seam for "current time" so the future-guard is deterministic in unit tests.
- Add unit tests covering multi-day rollover and the future-timestamp guard.

## Capabilities

### New Capabilities

<!-- None -->

### Modified Capabilities

- `onechip-wind-provider`: the **Timestamp derivation** requirement changes from "combine the single Arrived date with each row's `hh:mm`" to "reconstruct per-row dates across midnight boundaries and never emit a future-dated reading."

## Impact

- **Code:** `com.vb.wingfoil.provider.impl.OneChipDataProvider` — timestamp derivation in `parseMeasurements`/`parseRow`; add a package-private current-time seam and a future-guard step.
- **Tests:** `OneChipDataProviderTest` — new assertions/fixtures for multi-day rollover and the future-timestamp guard.
- **No** changes to the public HTTP API, controllers, `application.yml` configuration, dependencies, or other providers.
