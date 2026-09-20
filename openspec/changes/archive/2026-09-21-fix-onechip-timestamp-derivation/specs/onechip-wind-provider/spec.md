## MODIFIED Requirements

### Requirement: Timestamp derivation

The system SHALL derive each past-data row's timestamp from the row's `hh:mm` time combined with a **per-row calendar date**, not a single shared date. The newest row SHALL be anchored to the page's "Arrived" date (falling back to the current date when no parseable "Arrived" date exists); older rows SHALL have their date rolled back across midnight boundaries so a table spanning multiple days maps onto the correct dates. No returned reading SHALL carry a timestamp later than the current time.

#### Scenario: Date taken from the Arrived line for the newest row

- **WHEN** the page shows `Arrived: 06/09/2026 23:06:03` and the newest row is timestamped `23:06`
- **THEN** that reading's timestamp corresponds to 2026-09-06 23:06

#### Scenario: Multi-day table rolls back across midnight

- **WHEN** the newest-first rows include a newer row at `00:30` followed by an older row at `23:30` (wall-clock time increases as we move to older rows)
- **THEN** the `23:30` row is dated on the calendar day before the `00:30` row, and the returned readings remain ordered oldest-to-newest

#### Scenario: Missing Arrived date falls back to today

- **WHEN** the page has no parseable "Arrived" date
- **THEN** the provider anchors the newest row to the current date and derives the remaining rows relative to it

#### Scenario: No reading is dated in the future

- **WHEN** the reconstructed newest timestamp would fall after the current time by more than the tolerance
- **THEN** the provider shifts the whole series back by whole days so that no returned reading's timestamp is later than the current time, preserving the relative spacing between readings
