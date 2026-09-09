## Purpose

Lets the backend serve wind readings for anemometers that publish data only through 1chip.ru's HTML web page, by fetching and normalizing that page into the same readings other providers return.

## ADDED Requirements

### Requirement: Provider selection

The system SHALL expose a wind data provider named `onechip` and SHALL use it whenever a sensor's `provider` field equals `onechip`.

#### Scenario: Sensor routed to the 1chip provider

- **WHEN** a request targets a sensor whose `provider` is `onechip` and whose `id` is non-blank
- **THEN** the system builds the upstream call using the 1chip.ru provider

#### Scenario: Unknown provider still rejected

- **WHEN** a request targets a sensor whose `provider` is not a registered provider
- **THEN** the system fails the request with a "provider not found" error, as it does today

### Requirement: Upstream URL construction

The system SHALL construct the 1chip.ru call URL by substituting the sensor `id` into the configured base URL template `https://1chip.ru/windt.php?id=%s`.

#### Scenario: URL built from sensor id

- **WHEN** the sensor `id` is `0002`
- **THEN** the upstream request URL is `https://1chip.ru/windt.php?id=0002`

### Requirement: HTML parsing into normalized readings

The system SHALL parse the past-data table from the 1chip.ru HTML response and produce one normalized reading per data row, where each reading carries the minimum, average, and maximum (gust) wind speed, the numeric wind direction in degrees, and a timestamp.

#### Scenario: Valid page yields one reading per row

- **WHEN** the response is a valid 1chip.ru page containing N past-data rows
- **THEN** the provider returns N normalized readings whose min, average, max, and direction match the corresponding row values

#### Scenario: Readings returned in ascending time order

- **WHEN** the page lists past-data rows newest-first
- **THEN** the provider returns the readings ordered oldest-to-newest (ascending by timestamp)

### Requirement: Timestamp derivation

The system SHALL derive each reading's timestamp by combining the calendar date shown in the page's "Arrived" line with the row's `hh:mm` time.

#### Scenario: Date taken from the Arrived line

- **WHEN** the page shows `Arrived: 06/09/2026 23:06:03` and a row is timestamped `23:06`
- **THEN** that reading's timestamp corresponds to 2026-09-06 23:06

#### Scenario: Missing Arrived date falls back to today

- **WHEN** the page has no parseable "Arrived" date
- **THEN** the provider derives timestamps using the current date

### Requirement: Graceful handling of empty or malformed responses

The system SHALL return an empty result (a single empty reading) instead of failing the request when the response body is blank, contains no parseable past-data rows, or cannot be parsed. Individually malformed rows SHALL be skipped while valid rows are still returned.

#### Scenario: Blank body returns empty reading

- **WHEN** the response body is blank
- **THEN** the provider returns a single empty reading

#### Scenario: Malformed rows are skipped

- **WHEN** some past-data rows contain unparseable numeric values
- **THEN** those rows are omitted and the remaining valid rows are returned

### Requirement: Timed-readings windowing consistent with other providers

The system SHALL apply the same reading-window and number-of-readings reduction used by the other providers (default window 3600 seconds, default 5 readings), and SHALL return only the most recent reading when no window and no reading count are requested.

#### Scenario: Latest reading when no window requested

- **WHEN** `readingWindowSeconds` is 0 and `numberOfReadings` is 0
- **THEN** the provider returns only the most recent reading
