# onechip-wind-provider Specification

## Purpose
Defines how the backend serves wind readings for anemometers that publish only through the server-rendered HTML page at `https://1chip.ru/windt.php?id=<id>` — which page data it reads, how each row becomes a normalized timed reading, and what it reports when the upstream yields nothing.

## Requirements

### Requirement: Provider selection

The system SHALL expose a wind data provider named `onechip` and SHALL use it whenever a sensor's `provider` field equals `onechip`.

#### Scenario: Sensor routed to the 1chip provider

- **WHEN** a `/sensor-data` request targets a sensor whose `provider` is `onechip` and whose `id` is non-blank
- **THEN** the readings are produced by the 1chip provider

#### Scenario: Unknown provider still rejected

- **WHEN** a request targets a sensor whose `provider` is not a registered provider
- **THEN** the request fails with a "provider not found" error, as it does today

### Requirement: Upstream URL construction

The system SHALL build the 1chip call URL by substituting the sensor `id` into the configured URL template `https://1chip.ru/windt.php?id=%s`.

#### Scenario: URL built from sensor id

- **WHEN** the sensor `id` is `0264`
- **THEN** the upstream request URL is `https://1chip.ru/windt.php?id=0264`

### Requirement: Browser-like identity for page fetches

The system SHALL identify itself to 1chip.ru as an HTML page consumer: the call SHALL carry a browser-like `User-Agent`, SHALL accept HTML rather than JSON, and SHALL declare a language preference.

#### Scenario: Page request asks for HTML

- **WHEN** the provider fetches a sensor page
- **THEN** the outbound request declares an HTML `Accept` value and a non-empty `User-Agent`

#### Scenario: Provider-declared header replaces the generic one

- **WHEN** a provider declares a header that the shared proxy also sets by default
- **THEN** the outbound request carries the provider's value only, not two values for the same header

#### Scenario: Other providers are unaffected

- **WHEN** a sensor is served by a provider that declares no extra headers
- **THEN** its outbound request keeps the headers it has today

### Requirement: Past-data table selection

The system SHALL read past-data rows from the page's recent history table, and SHALL fall back to the page's grouped history table when the recent table provides no usable rows.

#### Scenario: Recent table preferred

- **WHEN** the page contains a recent history table with usable data rows
- **THEN** readings are taken from that table

#### Scenario: Grouped table used when the recent one is empty

- **WHEN** the recent history table has no usable data rows but the grouped history table has them
- **THEN** readings are taken from the grouped history table and reported as such in the provider's diagnostics

#### Scenario: Page with neither table

- **WHEN** the response contains no recognizable history table (for example a body that is only an empty page shell)
- **THEN** no readings are produced and the failure is classified as `NO_DATA_TABLE`

### Requirement: Header-driven column mapping

The system SHALL resolve each logical reading column — row time, minimum speed, average speed, gust speed, direction in degrees — from the table's header captions, accounting for header cells that span several columns, and SHALL NOT depend on a fixed cell position within a data row.

#### Scenario: Columns located by caption

- **WHEN** a history table's header cells are `hh:mm`, `min`, `Avg, m/s`, `Gust, m/s`, `Dir, °` with some header cells spanning two columns
- **THEN** each reading carries the values from the columns those captions describe

#### Scenario: Row with a trailing empty cell

- **WHEN** a data row carries one or more extra trailing cells after the mapped columns
- **THEN** the row is still read correctly

#### Scenario: Row too short to map

- **WHEN** a data row has fewer cells than the mapped columns require
- **THEN** that row is skipped and the remaining valid rows are returned

#### Scenario: Header cannot be recognized

- **WHEN** a table's header row does not identify the time, speed and direction columns
- **THEN** no readings are produced from that table and the failure is classified as `NO_DATA_TABLE`

### Requirement: One normalized reading per data row

The system SHALL produce one normalized reading per usable history row, carrying minimum, average and maximum (gust) wind speed, wind direction in degrees, and a timestamp.

#### Scenario: Values match the row

- **WHEN** a history row carries time `16:59`, min `9.4`, avg `10.7`, gust `12.5` and direction `22°`
- **THEN** the reading from that row has windMin 9.4, windAvg 10.7, windMax 12.5 and windDirection 22

#### Scenario: Malformed rows are skipped

- **WHEN** some history rows contain unparseable numeric or time values
- **THEN** those rows are omitted and the remaining valid rows are still returned

### Requirement: Readings ordered oldest to newest

The system SHALL return readings in ascending timestamp order even though the page lists history newest-first.

#### Scenario: Ascending despite newest-first page

- **WHEN** the page lists rows newest-first
- **THEN** the returned readings are ordered oldest-to-newest

### Requirement: Timestamp derivation from the page anchor

The system SHALL derive each row's timestamp from that row's `hh:mm` time combined with a per-row calendar date: the newest row is anchored to the calendar date shown in the page's "Arrived" line (falling back to the current date when no parseable "Arrived" date exists), and older rows have their date rolled back across midnight boundaries so a table spanning several days maps onto the correct dates.

#### Scenario: Anchor date from the Arrived line

- **WHEN** the page shows `Arrived: 17/11/2026 19:12:03` and the newest row is timestamped `19:12`
- **THEN** that reading's timestamp corresponds to 2026-11-17 19:12 in the sensor's local time zone

#### Scenario: Multi-day table rolls back across midnight

- **WHEN** the newest-first rows include a newer row at `00:30` followed by an older row at `23:30`
- **THEN** the `23:30` row is dated on the calendar day before the `00:30` row and the readings remain ascending

#### Scenario: Missing Arrived date falls back to today

- **WHEN** the page has no parseable "Arrived" date
- **THEN** the newest row is anchored to the current date and the remaining rows are derived relative to it

### Requirement: Sensor-local time zone

The system SHALL interpret the wall-clock times printed by the page in the configured sensor-local time zone (`wind-sensor.wind-providers.onechip.timezone`), falling back to the host's default zone when that setting is absent.

#### Scenario: Container runs in UTC while the sensor prints local time

- **WHEN** the host runs in UTC and the configured sensor zone is `Africa/Cairo`
- **THEN** a page row printed `19:12` yields the epoch-second timestamp of 19:12 Africa/Cairo, not 19:12 UTC

### Requirement: Bounded correction of future-dated readings

The system SHALL NOT return a reading dated in the future beyond a bounded clock-skew tolerance, and any correction it applies SHALL be no larger than the evidence justifies: whole-day roll-back only when the overshoot indicates a wrong date anchor, clamping to the current time otherwise.

#### Scenario: Small skew is not treated as misdated data

- **WHEN** the reconstructed newest reading lands after the current time by more than the tolerance but less than half a day
- **THEN** the series is NOT shifted by whole days, the offending readings are clamped to the current time, and the correction is logged

#### Scenario: Wrong date anchor is rolled back by whole days

- **WHEN** the reconstructed newest reading lands after the current time by at least half a day
- **THEN** the whole series is shifted back by the minimum number of whole days that brings it within the tolerance, preserving the spacing between readings, and the shift is logged

#### Scenario: Fresh data stays fresh

- **WHEN** the reconstructed newest reading is at or before the current time
- **THEN** no correction is applied and the returned timestamps equal the page's wall-clock times in the sensor zone

### Requirement: Classification of unproductive responses

The system SHALL classify every 1chip response that produced no readings and record one diagnostic entry per such response, naming the reason (`NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR`) together with the request URL, HTTP status, response body length, tables seen and rows parsed.

#### Scenario: Empty page shell is named as such

- **WHEN** 1chip.ru answers `200` with a body that contains only an empty page shell
- **THEN** the recorded reason is `EMPTY_PAGE_SHELL` and the diagnostic reports the tiny body length rather than a parse stack trace

#### Scenario: Layout change is distinguishable from an offline sensor

- **WHEN** 1chip.ru answers `200` with a full page whose history table no longer matches the expected column captions
- **THEN** the recorded reason is `NO_DATA_TABLE`, not `EMPTY_PAGE_SHELL` or `NO_VALID_ROWS`

#### Scenario: Diagnostics stay bounded

- **WHEN** a diagnostic entry is recorded
- **THEN** it reports the body length and table/row counts instead of embedding the whole response body

#### Scenario: Transport failure remains an error

- **WHEN** the upstream call itself fails (connection error or a non-2xx status)
- **THEN** the request fails as an upstream error, exactly as it does today, and is not reported as "no data"

### Requirement: Bounded upstream call duration

The system SHALL bound how long a 1chip upstream call may take — connect, response and connection-lease bounds taken from configuration — and SHALL issue exactly one upstream request per `/sensor-data` request.

#### Scenario: Stalled upstream fails as an upstream error

- **WHEN** the upstream accepts the connection but answers nothing within the configured response timeout
- **THEN** the call ends within its bounds and the request fails as an upstream error, instead of holding the request open indefinitely

#### Scenario: A refused upstream is not silently re-requested

- **WHEN** the upstream answers `503`
- **THEN** exactly one upstream request was issued for that client request and the call fails as an upstream error

### Requirement: Response shape when no readings are available

The system SHALL answer a `/sensor-data` request for a 1chip sensor that yielded no readings with exactly one reading whose wind values and timestamp are zero, so existing clients keep receiving the shape they already handle.

#### Scenario: Offline sensor keeps the current shape

- **WHEN** a valid request targets a 1chip sensor whose page yields no rows
- **THEN** the response is `200` with a single reading of `windMax=0, windAvg=0, windMin=0, windDirection=0, timestamp=0`

#### Scenario: Zero readings are not mixed with real ones

- **WHEN** at least one valid row was parsed
- **THEN** the response contains only readings derived from parsed rows and never the zero reading

### Requirement: Reading window and count reduction

The system SHALL apply the same reading-window and number-of-readings reduction used by the other providers (default window 3600 seconds, default 5 readings), measured from the newest available reading, and SHALL return only the most recent reading when neither a window nor a count is requested.

#### Scenario: Latest reading when no window requested

- **WHEN** `readingWindow` is 0 and `numberOfReadings` is 0
- **THEN** the provider returns only the most recent reading

#### Scenario: Window applied to the newest readings

- **WHEN** a page yields more rows than the requested window covers
- **THEN** the returned readings are the most recent ones within that window, reduced to the requested count with the first and last preserved
