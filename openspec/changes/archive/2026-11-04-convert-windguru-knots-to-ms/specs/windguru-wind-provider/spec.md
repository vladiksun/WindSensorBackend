# Spec Delta

## Purpose

Serves wind readings from anemometers published on Windguru station pages by normalizing the upstream
columnar payload into the backend's timed-reading contract, including converting Windguru's knot-based
speeds into metres per second.

## ADDED Requirements

<!-- Declared here as ADDED rather than MODIFIED because `openspec/specs/windguru-wind-provider/spec.md` does
not exist: `add-windguru-wind-provider` was archived without syncing its spec, and archive only accepts ADDED
requirements when creating a new capability spec. The requirement below is that archived requirement restated in
full, with the knot wording replaced by metres per second; it supersedes
"…carrying minimum, average and maximum (gust) wind speed in knots plus wind direction in degrees, without unit
conversion." -->

### Requirement: Columnar payload normalized into readings

The system SHALL convert Windguru's columnar arrays into one normalized reading per published timestamp,
carrying minimum, average and maximum (gust) wind speed in metres per second plus wind direction in degrees.

#### Scenario: Values mapped positionally

- **WHEN** the payload contains `wind_min`, `wind_avg`, `wind_max` and `wind_direction` arrays of equal length
- **THEN** reading *i* carries `windMin`, `windAvg`, `windMax` and `windDirection` taken from index *i* of the
  corresponding arrays, each speed converted to metres per second

#### Scenario: Published knots reported as metres per second

- **WHEN** Windguru publishes an average speed of 23.1 knots for a reading
- **THEN** that reading's average speed is 11.88 metres per second, not 23.1

### Requirement: Wind speed unit conversion

The system SHALL express every published Windguru wind speed — minimum, average and maximum (gust) — in
metres per second, using exactly one knot = 1852/3600 m/s (0.514444… m/s), and SHALL report each converted
speed rounded to two decimal places. Wind direction and reading timestamps SHALL be reported unchanged.

#### Scenario: Average speed converted

- **WHEN** a published reading carries an average speed of 23.1 knots
- **THEN** the returned reading's average speed is 11.88 metres per second

#### Scenario: Minimum and gust converted by the same factor

- **WHEN** a published reading carries a minimum of 20.2 knots and a maximum of 26.0 knots
- **THEN** the returned reading's minimum is 10.39 and its maximum is 13.38 metres per second

#### Scenario: Direction and timestamp untouched

- **WHEN** a published reading carries direction 10 degrees at epoch timestamp 1793785260
- **THEN** the returned reading still carries direction 10.0 and timestamp 1793785260

#### Scenario: Speed ordering preserved

- **WHEN** a published reading has minimum ≤ average ≤ maximum in knots
- **THEN** the same reading keeps minimum ≤ average ≤ maximum after conversion

#### Scenario: Each value converted exactly once

- **WHEN** the same station payload is requested as a windowed series and, separately, as a latest-only
  request (`readingWindow` 0 and `numberOfReadings` 0)
- **THEN** the newest reading reports the same converted speeds in both responses, never a value converted a
  second time (for published average 24.3 knots: 12.50 in both, not 6.43)

#### Scenario: Missing and empty readings stay empty

- **WHEN** a payload row publishes a null wind value, or the payload carries no data at all
- **THEN** the provider still skips that row or returns its single empty reading, whose speeds are zero
