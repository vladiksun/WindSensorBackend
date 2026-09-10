# osm-tile-proxy Spec Delta

## Purpose

Provide a compliant, caching proxy for OpenStreetMap slippy-map tiles so that client applications (the WindSensor watch app) can render maps without violating OSM's tile usage policy: the backend identifies itself with proper HTTP headers, caches tiles locally for at least 7 days, and serves only the single requested tile.

## ADDED Requirements

### Requirement: Single-tile fetch endpoint

The system SHALL expose `GET /tiles/{z}/{x}/{y}.png` where `z`, `x`, `y` are non-negative integers. On success it SHALL return HTTP 200 with `Content-Type: image/png` and the exact PNG bytes of the upstream tile `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. The upstream base URL MUST be configurable via `application.yml` (default `https://tile.openstreetmap.org`). No endpoint accepting bounding boxes, multiple tiles, or zoom ranges SHALL exist.

#### Scenario: Valid tile is served as PNG

- **WHEN** a client requests `GET /tiles/15/16384/20480.png`
- **THEN** the response is HTTP 200, `Content-Type: image/png`, and the body starts with the PNG magic bytes (`89 50 4E 47`)

#### Scenario: Upstream failure is propagated as an error

- **WHEN** the upstream tile source returns a non-2xx status or is unreachable
- **THEN** the proxy responds with a 5xx status (not a fabricated PNG) and does NOT store anything in the local cache

#### Scenario: No bulk tile endpoints exist

- **WHEN** the route table is inspected
- **THEN** the only tile-serving route is `GET /tiles/{z}/{x}/{y}.png`; there is no route accepting a bbox, a list of tiles, or a zoom range

### Requirement: Identifying outbound HTTP headers

Every request the proxy sends to the upstream tile source SHALL include a `User-Agent` header of the form `WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: <email>)` and a valid `Referer` header identifying the application. The User-Agent and Referer values MUST be taken from configuration (never a library default, never impersonating a browser). Outbound requests SHALL NOT send `Cache-Control: no-cache` by default.

#### Scenario: Outbound request carries configured headers

- **WHEN** the proxy fetches a tile from the upstream source
- **THEN** the outbound HTTP request contains exactly the configured `User-Agent` and `Referer` values and no `no-cache` directive

#### Scenario: Header values are externally verifiable

- **WHEN** the outbound headers are logged or captured by a test double
- **THEN** the recorded `User-Agent` matches the configured value and is not a generic library default (e.g. Apache-HttpClient or okhttp defaults)


### Requirement: Local tile caching honouring upstream cache headers

The proxy SHALL cache fetched tiles locally on the file system keyed by `z/x/y`, using EhCache as the storage engine. Cache validity SHALL be derived from the upstream response's `Cache-Control`, `Expires`, and `Etag` headers; when those headers are absent or unreadable, the entry SHALL remain valid for a minimum of 7 days. A second request for the same tile within its validity window SHALL be served from the local cache without contacting the upstream source. Expired entries SHALL be refetched from upstream. When a cached entry carries an upstream `Etag`, the refetch MAY use a conditional request (`If-None-Match`); a `304 Not Modified` response SHALL refresh the entry's validity without re-downloading the body.

#### Scenario: Second request within validity window is served from cache

- **WHEN** a tile has been fetched once and a second request arrives for the same `z/x/y` while the entry is still valid
- **THEN** the tile bytes are returned from the local cache, no upstream request is made, and the response indicates a cache hit (e.g. `X-Cache: HIT` vs `MISS`)

#### Scenario: Unreadable cache headers fall back to 7-day TTL

- **WHEN** an upstream response lacks usable `Cache-Control`/`Expires` headers
- **THEN** the cached entry is considered valid for at least 7 days before being refetched

#### Scenario: Forced-expired entry is refetched

- **WHEN** a cached entry's validity window has elapsed (or the entry is invalidated)
- **THEN** the next request triggers an upstream fetch and the stored entry is replaced with fresh bytes and a fresh validity window

#### Scenario: Conditional refetch with Etag

- **WHEN** a cached entry with an upstream `Etag` expires and is refetched
- **THEN** the refetch sends `If-None-Match` with the stored Etag, and a `304 Not Modified` response keeps the existing bytes and extends validity

### Requirement: Cache persistence across restarts

The EhCache storage directory SHALL be configurable and mapped as a volume in `dev_setup/docker-compose.yml` so that cached tiles survive container restarts.

#### Scenario: Volume mapping present in compose file

- **WHEN** `dev_setup/docker-compose.yml` is inspected
- **THEN** a host volume is mounted at the configured EhCache storage path used by the service

### Requirement: Visible licence attribution

The API documentation of the tile endpoint SHALL display "© OpenStreetMap contributors" together with a reference to `openstreetmap.org/copyright`, per the OSMF Attribution Guidelines, so anyone consuming the API is aware the data originates from OpenStreetMap.

#### Scenario: OpenAPI docs carry attribution

- **WHEN** the generated OpenAPI specification is inspected
- **THEN** the `/tiles/{z}/{x}/{y}.png` operation description includes "© OpenStreetMap contributors" and the `openstreetmap.org/copyright` reference

### Requirement: Integration test against the real tile source

An integration test SHALL perform a real end-to-end tile fetch against `tile.openstreetmap.org` using the fixed GPS coordinates `[60.068347, 30.002349]` and the same Web-Mercator `{z}/{x}/{y}` calculation as the watch app (`MapTileLoader.computeTiles` at zoom 15), asserting a valid PNG is returned and that a repeat request is served from cache.

#### Scenario: Real tile for the fixed coordinate resolves and renders

- **WHEN** the test computes the centre tile for latitude `60.068347`, longitude `30.002349` at zoom `15` using the Web-Mercator formula and requests it through the proxy
- **THEN** the proxy returns HTTP 200 with a valid PNG body from the real upstream source

#### Scenario: Repeat request hits the cache

- **WHEN** the test requests the same tile a second time immediately after the first successful fetch
- **THEN** the second response is served from the local cache (cache-hit indicator set, identical bytes)
