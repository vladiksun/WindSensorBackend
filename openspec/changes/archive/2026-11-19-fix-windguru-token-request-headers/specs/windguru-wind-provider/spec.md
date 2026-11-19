# Spec Delta

## ADDED Requirements

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
