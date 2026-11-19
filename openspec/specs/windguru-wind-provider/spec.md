# windguru-wind-provider Specification

## Purpose
Serves wind readings from anemometers published on Windguru station pages by normalizing the upstream
columnar payload into the backend's timed-reading contract, including converting Windguru's knot-based
speeds into metres per second.

## Requirements

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

### Requirement: Windguru provider identity remains `windguru`

The system SHALL continue to expose the Windguru wind provider under the external provider name `windguru`, so `/sensor-data` routing and provider configuration do not change when its implementation class is renamed.

#### Scenario: Existing Windguru sensors keep using the same provider code

- **WHEN** a `/sensor-data` request contains a sensor whose `provider` is `windguru`
- **THEN** the request is served by the Windguru provider behavior described by this capability

#### Scenario: Provider configuration key remains unchanged

- **WHEN** an operator configures the Windguru upstream endpoint under `wind-sensor.wind-providers.windguru`
- **THEN** that configuration selects the renamed Windguru provider without a client-visible configuration change

### Requirement: Self-minted station-data token

The system SHALL obtain a Windguru access token from the upstream token-refresh action before issuing a Windguru `station_data` request whenever no valid cached token is available. The token acquisition SHALL work with plain HTTP and SHALL NOT require cookies, browser automation, or an official API key.

#### Scenario: Token is minted when station data is requested

- **WHEN** a Windguru sensor request needs to call `station_data` and the provider has no valid cached access token
- **THEN** the system first obtains an access token from the token-refresh action

#### Scenario: Cookie-less plain HTTP can satisfy Windguru

- **WHEN** the token-refresh action returns a successful token payload over plain HTTP
- **THEN** the provider uses that returned token for subsequent station-data requests without maintaining a cookie session

#### Scenario: The token request names a page of the upstream site

- **WHEN** the provider calls the token-refresh action
- **THEN** that request carries a referring header naming a page of the same site the configured endpoint lives on, because the live token action answers `401` to a token request that names no referring page

#### Scenario: The minted token is not bound to the station it was minted for

- **WHEN** a token was minted while referring to one Windguru page and is then sent on `station_data` for a different station id
- **THEN** the provider still uses that same cached token, because the upstream accepts it for any station

### Requirement: Mandatory Windguru station-data headers

The system SHALL send both a referring header naming the requested Windguru station page and an `X-WG-Token` header carrying the currently valid Windguru access token on every outbound Windguru `station_data` request.

#### Scenario: A station request carries both required headers

- **WHEN** a `/sensor-data` request targets sensor id `15693` with provider `windguru`
- **THEN** the outbound station-data request carries a referring header naming `https://www.windguru.cz/station/15693` and an `X-WG-Token` header carrying a valid token

#### Scenario: The referring header follows the requested sensor id

- **WHEN** a Windguru request targets a different non-blank station id
- **THEN** the referring header names that station's page while the `X-WG-Token` header still carries the provider's current valid token

### Requirement: Short-lived token cache

The system SHALL reuse a valid cached Windguru access token for later station-data requests and SHALL refresh it only when no unexpired cached token is available. The effective cached lifetime MUST be shorter than the upstream token lifetime advertised by the token response.

#### Scenario: Repeated valid requests do not mint a new token

- **WHEN** several Windguru station-data requests occur while the cached access token is still valid
- **THEN** they use the same cached token and issue no additional token-refresh request

#### Scenario: An expired token is refreshed once

- **WHEN** a Windguru station-data request occurs after the cached access token has expired
- **THEN** the provider obtains one replacement token before issuing the station-data request

#### Scenario: Upstream lifetime is not trusted beyond its advertised validity

- **WHEN** a token response advertises a time-to-live of 1800 seconds
- **THEN** the provider marks that token as expired strictly before 1800 seconds have elapsed

### Requirement: Token acquisition failures do not fabricate readings

When the system cannot obtain a valid Windguru access token, it SHALL NOT fabricate wind readings and SHALL NOT issue an unauthenticated `station_data` request. It SHALL report the failure through the existing `/sensor-data` upstream failure path with a sanitized client response.

#### Scenario: Token endpoint unavailable fails the sensor request

- **WHEN** no cached token is valid and the token-refresh request cannot be completed
- **THEN** `/sensor-data` fails as an upstream failure, stores nothing in the response cache, and returns no fabricated reading list

#### Scenario: Invalid token payload fails the sensor request

- **WHEN** the token-refresh action answers with a body that does not contain a usable access token
- **THEN** `/sensor-data` fails through the existing sanitized upstream-failure contract rather than sending an unauthenticated station-data call
