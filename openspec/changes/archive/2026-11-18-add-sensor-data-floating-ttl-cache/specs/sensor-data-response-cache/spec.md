# Spec Delta

## Purpose

Defines how the backend answers `POST /sensor-data` from a local response cache: which key identifies a cached response, how long an entry stays valid and how that validity is randomised per entry, which outcomes may be cached, how concurrent requests share one upstream fetch, and what a client can observe.

## ADDED Requirements

### Requirement: Cache-first sensor data responses

The system SHALL answer `POST /sensor-data` from the local response cache whenever a valid entry exists for that request, and SHALL reach the provider only when no valid entry exists. The readings returned to the client SHALL be identical whether they came from the cache or from a fresh provider call.

#### Scenario: Repeated request inside the validity window is served from cache

- **WHEN** two identical `/sensor-data` requests for the same provider, sensor and reduction parameters arrive while the entry stored by the first one is still valid
- **THEN** the second response carries the same readings and issues no additional upstream call

#### Scenario: Request after expiry refetches from the provider

- **WHEN** an identical request arrives after the stored entry has expired
- **THEN** exactly one upstream call is issued and the response carries the freshly parsed readings

#### Scenario: One client poll per validity window per sensor

- **WHEN** a client polls one sensor every 12 seconds for 5 minutes with caching enabled at the default TTL
- **THEN** between one and two upstream calls were issued for that sensor over that period, instead of twenty-five

### Requirement: Separate short-lived response cache

The system SHALL keep cached sensor responses in a cache that is separate from the map-tile caches, and SHALL NOT persist them to disk: an entry exists only for its validity window and is gone after a restart.

#### Scenario: Tile caches are untouched

- **WHEN** sensor responses are cached
- **THEN** no sensor reading is stored in either tile cache and tile serving behaves exactly as before

#### Scenario: A restart does not serve stale readings

- **WHEN** the service restarts and a request arrives for a sensor that was cached seconds earlier
- **THEN** that request reaches upstream instead of returning data carried over from the previous process

### Requirement: Cache identity covers provider, sensor and reduction parameters

A cached response SHALL be identified by the provider name, the sensor id, and the effective reading window and number of readings — resolved from the request body, then the sensor's own configuration, then the defaults. Entries MUST NOT be shared between different providers, sensors or parameter combinations.

#### Scenario: Same id under two providers stays separate

- **WHEN** a sensor id `0264` is requested once as `onechip` and once as `windy`
- **THEN** each request has its own cache entry and neither response can be served from the other's entry

#### Scenario: Different windows or counts are different entries

- **WHEN** the same sensor is requested with `readingWindow=3600, numberOfReadings=5` and then with `readingWindow=7200, numberOfReadings=2`
- **THEN** the second request is a cache miss and reaches upstream

#### Scenario: Sensor-level configuration decides the identity

- **WHEN** a request omits `readingWindow` while the sensor's own configuration pins it to `7200`
- **THEN** the entry is identified by the effective value `7200`, and an explicit `3600` request for the same sensor is a separate entry

#### Scenario: An unknown provider still fails and caches nothing

- **WHEN** a request names a provider that is not registered
- **THEN** the request fails with the same "provider not found" error as today and nothing is stored

### Requirement: Configurable base validity defaulting to five minutes

The system SHALL keep an entry valid for a configurable base time-to-live whose default is 5 minutes, settable through `wind-sensor.sensor-data-cache.ttl` and the `CACHE_TTL` environment variable.

#### Scenario: Default applies when nothing is configured

- **WHEN** neither the configuration key nor the environment variable is set
- **THEN** the base validity used for every entry is 5 minutes

#### Scenario: Operator shortens the TTL

- **WHEN** `CACHE_TTL` is set to `90s`
- **THEN** entries stop being valid after about 90 seconds instead of 5 minutes, within the floating window described below

### Requirement: Floating validity window per entry

The system SHALL randomise each entry's expiry: at store time it draws a jitter uniformly from `0` up to the configured floating window (`wind-sensor.sensor-data-cache.floating-window`, default 1 minute, env `CACHE_TTL_FLOATING_WINDOW`) and applies it to the base TTL in either direction, so an entry stays valid for somewhere within `ttl ± window`. The draw SHALL be made per entry, not once per process.

#### Scenario: Every expiry stays inside the configured band

- **WHEN** entries are stored with a 5-minute base TTL and a 1-minute floating window
- **THEN** each entry's expiry instant is between 4 and 6 minutes after it was stored

#### Scenario: Stored expiries are not identical

- **WHEN** several entries are stored back to back under the same configuration
- **THEN** their expiry instants differ, so the refetch times those entries produce are not a single repeated interval

#### Scenario: A zero window removes the floating behaviour

- **WHEN** `CACHE_TTL_FLOATING_WINDOW` is set to `0`
- **THEN** every entry expires exactly one base TTL after it was stored

### Requirement: Caching applies to every provider without provider changes

The system SHALL apply caching in the shared request path so that every registered provider is cached by the same behaviour. Provider implementations SHALL NOT need any caching code, and a provider registered later SHALL be cached automatically. The outbound request identity a provider declares SHALL be sent unchanged whenever a fetch does happen.

#### Scenario: Each registered provider is served from cache

- **WHEN** a repeated `windy` request and a repeated `onechip` request each arrive twice within their entry's validity
- **THEN** both second requests are answered from the cache with no additional upstream call

#### Scenario: A provider added later is cached without touching the cache

- **WHEN** a new provider is registered and its sensor is requested twice within the validity window
- **THEN** the second request is served from the cache, and no caching code had to change to accommodate that provider

#### Scenario: Declared headers still travel on a fetch

- **WHEN** a cache miss causes a `onechip` or `windguru` fetch
- **THEN** the outbound upstream request carries exactly the headers that provider declares today, with no duplicates

### Requirement: Only successful provider responses are cached

The system SHALL store an entry only after a successful provider call and MUST NOT store anything for a failed request. A successful response that produced no usable readings — the single zero-valued reading — SHALL be cached like any other success.

#### Scenario: A refused upstream caches nothing

- **WHEN** the provider answers `503`
- **THEN** the client receives the same upstream error response as today, nothing is stored, and the next identical request reaches upstream again

#### Scenario: An unreachable upstream caches nothing

- **WHEN** the provider call fails on connect or on its response timeout
- **THEN** the request fails as an upstream error and no entry is created for that key

#### Scenario: An offline sensor stops being re-polled

- **WHEN** a `200` provider response yields no usable readings and a second identical request arrives within the entry's validity
- **THEN** the second response carries the same single zero-valued reading and issues no additional upstream call

### Requirement: One upstream call per expired key under concurrency

When several requests that resolve to the same cache key arrive while that key has no valid entry, the system SHALL issue at most one upstream call for that key and SHALL return the same readings to every waiting request. Requests for different keys SHALL NOT wait on each other's upstream call.

#### Scenario: A burst after an expiry triggers a single fetch

- **WHEN** five identical `/sensor-data` requests arrive concurrently while the key has no valid entry
- **THEN** exactly one upstream call is issued and all five responses carry the same readings

#### Scenario: Unrelated sensors are fetched independently

- **WHEN** requests for two different sensors miss concurrently
- **THEN** each sensor's provider call is issued, and neither request waits for the other

### Requirement: Failures and cache status keep the existing contract

The system SHALL report whether a response came from the cache through an `X-Cache` response header (`HIT` or `MISS`) while keeping the `/sensor-data` request body, response body and error responses exactly as they are today. Clients that ignore response headers SHALL see no difference.

#### Scenario: Fresh fetch reports MISS

- **WHEN** a request caused an upstream fetch
- **THEN** the response carries `X-Cache: MISS` and the same JSON body it would have carried before this change

#### Scenario: Cached answer reports HIT with an identical body

- **WHEN** a request is answered from a valid entry
- **THEN** the response carries `X-Cache: HIT` and a JSON body equal to the one produced by the fetch that stored the entry

#### Scenario: Upstream failures keep their current shape

- **WHEN** a cache miss ends in an upstream failure
- **THEN** the client receives the same error status and JSON error body as today

### Requirement: Response caching can be switched off

The system SHALL let an operator disable response caching through `wind-sensor.sensor-data-cache.enabled` (default `true`); with caching disabled, `/sensor-data` SHALL behave exactly as it does today.

#### Scenario: Disabled caching restores one call per request

- **WHEN** caching is disabled and the same sensor is requested ten times
- **THEN** ten upstream calls are issued and no entry is read or written

#### Scenario: Production default caches

- **WHEN** the application starts with the shipped configuration and no `enabled` override
- **THEN** response caching is active
