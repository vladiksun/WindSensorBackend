# Spec Delta

## MODIFIED Requirements

### Requirement: Only successful provider responses are cached

The system SHALL store an entry only after a successful provider call and MUST NOT store anything for a failed request. A successful response that produced no usable readings — the single zero-valued reading — SHALL be cached like any other success.

#### Scenario: A refused upstream caches nothing

- **WHEN** the provider answers `503`
- **THEN** the client receives HTTP `503` with a short sanitized error description, nothing is stored, and the next identical request reaches upstream again

#### Scenario: An unreachable upstream caches nothing

- **WHEN** the provider call fails on connect or on its response timeout before an upstream HTTP status is available
- **THEN** the client receives HTTP `502` with a short sanitized error description and no entry is created for that key

#### Scenario: An offline sensor stops being re-polled

- **WHEN** a `200` provider response yields no usable readings and a second identical request arrives within the entry's validity
- **THEN** the second response carries the same single zero-valued reading and issues no additional upstream call

### Requirement: Failures and cache status keep the existing contract

The system SHALL report whether a successful `/sensor-data` response came from the cache through an `X-Cache` response header (`HIT` or `MISS`) while keeping the request body and successful response body unchanged. Failed upstream calls MUST follow the upstream failure mapping requirements below. Clients that ignore response headers SHALL see no difference for successful requests.

#### Scenario: Fresh fetch reports MISS

- **WHEN** a request caused an upstream fetch
- **THEN** the response carries `X-Cache: MISS` and the same JSON body it would have carried before this change

#### Scenario: Cached answer reports HIT with an identical body

- **WHEN** a request is answered from a valid entry
- **THEN** the response carries `X-Cache: HIT` and a JSON body equal to the one produced by the fetch that stored the entry

#### Scenario: Upstream failures keep their current shape

- **WHEN** a cache miss ends in an upstream failure
- **THEN** the client receives the mapped HTTP status and short sanitized error description required by this specification, and nothing is cached

## ADDED Requirements

### Requirement: Upstream HTTP error status propagation

The system SHALL return the same HTTP status code that a provider returned when fetching `/sensor-data` readings fails with an upstream HTTP error status.

#### Scenario: A forbidden provider response is returned as forbidden

- **WHEN** a provider answers `403` while `/sensor-data` is being fetched from it
- **THEN** the client receives HTTP `403`

#### Scenario: An unavailable provider response is returned as unavailable

- **WHEN** a provider answers `503` while `/sensor-data` is being fetched from it
- **THEN** the client receives HTTP `503`

### Requirement: Transport failure mapping

The system SHALL return `502 Bad Gateway` when a `/sensor-data` provider call fails before an upstream HTTP status is available.

#### Scenario: Connect failure maps to bad gateway

- **WHEN** the provider cannot be reached and no upstream response status is available
- **THEN** the client receives HTTP `502`

#### Scenario: Response timeout maps to bad gateway

- **WHEN** a stalled provider exceeds the configured upstream response timeout
- **THEN** the client receives HTTP `502`

### Requirement: Sanitized client error messages

The client-visible error body for `/sensor-data` upstream failures SHALL contain only a short description suitable for clients and MUST NOT expose provider URLs, query parameters, raw upstream payload text, or internal cause details.

#### Scenario: Forbidden response hides internal request details

- **WHEN** a provider answers `403` for an internal fetch URL containing host, path, and query details
- **THEN** the client response still has HTTP `403`, but its JSON error message does not contain that provider URL or query string

### Requirement: Upstream diagnostics preserve the original cause

The system SHALL preserve the original upstream failure as the cause of the client-facing application failure and SHALL log it for server-side diagnosis.

#### Scenario: Safe response still has an internal diagnostic cause

- **WHEN** a provider answers `403` with full internal request details available only to logs
- **THEN** the server logs contain the original upstream status failure, while the client response remains sanitized
