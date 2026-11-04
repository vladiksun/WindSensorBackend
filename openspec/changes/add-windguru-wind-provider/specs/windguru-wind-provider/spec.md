# Spec Delta

## Purpose

Lets the backend serve wind readings for anemometers published on Windguru station pages, by calling Windguru's per-station history endpoint and normalizing its columnar payload into the same timed readings every other provider returns.

## ADDED Requirements

### Requirement: Provider selection

The system SHALL expose a wind data provider named `windguru` and SHALL use it whenever a sensor's `provider` field equals `windguru`.

#### Scenario: Sensor routed to the Windguru provider

- **WHEN** a `/sensor-data` request targets a sensor whose `provider` is `windguru` and whose `id` is a non-blank Windguru station number
- **THEN** the system builds the upstream call with the Windguru provider and returns normalized readings

#### Scenario: Unknown provider still rejected

- **WHEN** a request targets a sensor whose `provider` is not a registered provider
- **THEN** the system fails the request with a "provider not found" error, as it does today

### Requirement: Upstream history URL construction

The system SHALL build the Windguru call from the configured base URL template by substituting the station `id` and adding an explicit time range whose end is the current instant and whose start is a fixed look-back window, expressed as ISO-8601 UTC timestamps.

#### Scenario: URL built from station id

- **WHEN** the sensor `id` is `15693`
- **THEN** the upstream request targets the configured Windguru history endpoint with station id `15693` and a `from`/`to` time range ending at the current instant

#### Scenario: Time range covers any supported reading window

- **WHEN** a request asks for a reading window no larger than the provider's look-back window
- **THEN** the upstream range fully contains that reading window, so windowing is not limited by the fetched range

### Requirement: Columnar payload normalized into readings

The system SHALL convert Windguru's columnar arrays into one normalized reading per published timestamp, carrying minimum, average and maximum (gust) wind speed in knots plus wind direction in degrees, without unit conversion.

#### Scenario: Values mapped positionally

- **WHEN** the payload contains `wind_min`, `wind_avg`, `wind_max` and `wind_direction` arrays of equal length
- **THEN** reading *i* carries `windMin`, `windAvg`, `windMax` and `windDirection` taken from index *i* of the corresponding arrays

#### Scenario: Published speeds reported unchanged

- **WHEN** Windguru publishes an average speed of 23.3 knots
- **THEN** the returned reading's average speed is 23.3, with no unit conversion applied

### Requirement: Timestamps taken from published epoch values

The system SHALL use the epoch-second timestamps published by Windguru as each reading's timestamp and SHALL NOT derive timestamps from the payload's local-time text or the server's time zone.

#### Scenario: Epoch timestamp preserved

- **WHEN** a published reading carries epoch timestamp 1793785200
- **THEN** that reading's timestamp is 1793785200 regardless of the host time zone

#### Scenario: Readings returned in ascending time order

- **WHEN** the payload lists readings oldest-to-newest
- **THEN** the provider returns the readings ordered oldest-to-newest by timestamp

### Requirement: Readings with missing values are skipped

The system SHALL omit any published timestamp whose wind value is missing or null in any of the four wind fields, and SHALL still return every complete reading.

#### Scenario: Gap row skipped

- **WHEN** the payload contains 100 timestamps of which 20 have a null average speed
- **THEN** the provider returns 80 readings and none of them carry the timestamps of the incomplete rows

### Requirement: Graceful handling of empty or unusable responses

The system SHALL return a single empty reading instead of failing the request when the response body is blank, when it carries no data rows, or when its shape cannot be interpreted as station data.

#### Scenario: No-data payload returns empty reading

- **WHEN** the upstream responds successfully with an empty JSON array (the shape Windguru returns for a range with no data)
- **THEN** the provider returns a single empty reading

#### Scenario: Blank body returns empty reading

- **WHEN** the response body is blank
- **THEN** the provider returns a single empty reading

### Requirement: Timed-readings windowing consistent with other providers

The system SHALL apply the shared reading-window and number-of-readings reduction used by the other providers (default window 3600 seconds, default 5 readings), and SHALL return only the most recent reading when no window and no reading count are requested.

#### Scenario: Latest reading when no window requested

- **WHEN** `readingWindow` is 0 and `numberOfReadings` is 0
- **THEN** the provider returns only the most recent reading

#### Scenario: Windowed request reduced to the requested count

- **WHEN** a request asks for a 3600-second window with 5 readings and the payload holds more than 5 complete readings inside that window
- **THEN** the provider returns at most 5 readings spanning the window, oldest-to-newest

### Requirement: Provider-declared upstream headers

The system SHALL send the additional request headers declared by a provider on the outbound upstream call. For Windguru these headers MUST name a Windguru station page as the referring page, because Windguru rejects data-endpoint calls that carry no referring page.

#### Scenario: Referring header present on the outbound call

- **WHEN** a request targets sensor `15693` with provider `windguru`
- **THEN** the upstream call carries a referring header naming a Windguru station page, and the data is returned instead of an 401 Unauthorized failure

#### Scenario: Providers that declare no extra headers are unaffected

- **WHEN** a request targets a provider that declares no additional headers
- **THEN** the upstream call is sent with exactly the headers it has today

### Requirement: Upstream failures reported through the existing error path

The system SHALL surface non-success Windguru responses as an upstream-call failure handled by the existing global error handling, without inventing new response bodies.

#### Scenario: Unknown station id

- **WHEN** Windguru answers 400 for a station id that does not exist
- **THEN** the request fails with the existing "Upstream call failed with status 400" error and an internal-server error response, as for any other provider failure


