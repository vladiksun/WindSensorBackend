## Why

The tile proxy always serves a valid cached tile without contacting upstream. There is no way to force a fresh fetch of a single tile (e.g. after OSM map data changed, or when debugging stale tiles) without restarting the service or waiting out the validity window. A client-side opt-in escape hatch lets consumers pull a guaranteed-fresh tile on demand.

## What Changes

- `GET /tiles/{z}/{x}/{y}.png` gains an optional boolean query parameter `skipCache` (default `false`), e.g. `https://localhost:443/tiles/15/19114/9503.png?skipCache=true`.
- When `skipCache=true`, the local cache is not consulted: the tile is always fetched from the upstream source. The freshly fetched tile is still stored in the local cache so subsequent normal requests hit it.
- When `skipCache` is absent or `false`, behavior is unchanged (cache-first).
- OpenAPI documentation of the endpoint documents the new parameter.

No breaking changes: existing clients that do not send the parameter keep the current cache-first behavior.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `osm-tile-proxy`: the single-tile fetch endpoint requirement changes — the endpoint now accepts an optional `skipCache` query parameter that forces an upstream fetch while still populating the local cache.

## Impact

- `src/main/java/com/vb/wingfoil/tiles/TileProxyController.java` — new `@QueryValue` parameter, passed down; OpenAPI annotations updated.
- `src/main/java/com/vb/wingfoil/tiles/OsmTileService.java` — `getTile` gains a `skipCache` flag controlling whether the cache lookup short-circuits the upstream fetch.
- Tests under `src/test/java/com/vb/wingfoil/tiles/` — unit/integration coverage for the new parameter.
- No configuration, dependency, or deployment changes.
