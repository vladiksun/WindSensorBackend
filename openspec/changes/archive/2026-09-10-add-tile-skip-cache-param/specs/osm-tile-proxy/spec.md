# osm-tile-proxy Spec Delta

## MODIFIED Requirements

### Requirement: Single-tile fetch endpoint

The system SHALL expose `GET /tiles/{z}/{x}/{y}.png` where `z`, `x`, `y` are non-negative integers. On success it SHALL return HTTP 200 with `Content-Type: image/png` and the exact PNG bytes of the upstream tile `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. The upstream base URL MUST be configurable via `application.yml` (default `https://tile.openstreetmap.org`). No endpoint accepting bounding boxes, multiple tiles, or zoom ranges SHALL exist.

The endpoint SHALL accept an optional boolean query parameter `skipCache` (e.g. `GET /tiles/15/19114/9503.png?skipCache=true`). When `skipCache` is absent or `false`, the cache-first behavior defined by the local tile caching requirement applies unchanged. When `skipCache=true`, the proxy SHALL NOT serve the tile from the local cache and SHALL fetch the tile from the upstream source; the freshly fetched tile SHALL still be stored in the local cache so that subsequent requests without `skipCache` can be served from it. The OpenAPI documentation of the endpoint SHALL document the `skipCache` parameter.

#### Scenario: Valid tile is served as PNG

- **WHEN** a client requests `GET /tiles/15/16384/20480.png`
- **THEN** the response is HTTP 200, `Content-Type: image/png`, and the body starts with the PNG magic bytes (`89 50 4E 47`)

#### Scenario: Upstream failure is propagated as an error

- **WHEN** the upstream tile source returns a non-2xx status or is unreachable
- **THEN** the proxy responds with a 5xx status (not a fabricated PNG) and does NOT store anything in the local cache

#### Scenario: No bulk tile endpoints exist

- **WHEN** the route table is inspected
- **THEN** the only tile-serving route is `GET /tiles/{z}/{x}/{y}.png`; there is no route accepting a bbox, a list of tiles, or a zoom range

#### Scenario: Request without skipCache keeps cache-first behavior

- **WHEN** a tile has been cached and a request arrives without the `skipCache` parameter (or with `skipCache=false`) while the entry is still valid
- **THEN** the tile is served from the local cache exactly as before this change

#### Scenario: skipCache=true forces an upstream fetch

- **WHEN** a valid cached entry exists for a tile and a request arrives with `skipCache=true`
- **THEN** the proxy fetches the tile from the upstream source instead of serving the cached bytes, and the response indicates the tile came from upstream (e.g. `X-Cache: MISS`)

#### Scenario: skipCache=true still populates the local cache

- **WHEN** a request with `skipCache=true` successfully fetches a tile from upstream
- **THEN** the fetched tile is stored in the local cache, and a subsequent request for the same tile without `skipCache` within the validity window is served from the cache
