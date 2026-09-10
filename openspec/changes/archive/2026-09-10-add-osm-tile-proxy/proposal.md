# Proposal: Add OSM Caching Tile Proxy

## Why

The WindSensor watch app renders OpenStreetMap tiles directly from `tile.openstreetmap.org`, which violates OSM's tile usage policy (no identifying User-Agent, no Referer, no server-side caching). To stay compliant with the OSMF Attribution Guidelines and the tile usage policy, the backend must act as a caching proxy behind `serverUrl`: it identifies itself to the tile source, caches tiles locally for at least 7 days, and serves only the single requested tile. This is part 1 of the `fix-osm-tile-compliance` plan (backend side); the watch-side changes live in the WindSensor repo.

## What Changes

- New REST route `GET /tiles/{z}/{x}/{y}.png` that fetches upstream `https://tile.openstreetmap.org/{z}/{x}/{y}.png` and returns the tile as `image/png`.
- Outbound requests to the tile source always carry an application-identifying `User-Agent` (`WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: <email>)`) and a valid `Referer` header — never a library default, never browser impersonation.
- Local file-system tile cache built on **EhCache** via Micronaut Cache, keyed by `z/x/y`, honouring upstream `Cache-Control`/`Expires`/`Etag` headers and falling back to a minimum 7-day TTL when they are unreadable. No `no-cache` is sent upstream by default.
- Upstream tile base URL, User-Agent and Referer values externalized into `src/main/resources/application.yml` (env-overridable).
- EhCache storage directory added to `dev_setup/docker-compose.yml` as a volume so cached tiles survive container restarts.
- Visible licence attribution: OpenAPI documentation of the `/tiles` endpoint carries "© OpenStreetMap contributors" plus the `openstreetmap.org/copyright` reference (the on-device map attribution itself is delivered by the watch app, part 3 of the compliance plan).
- Integration test that performs a real tile fetch against `tile.openstreetmap.org` using the same fixed GPS coordinates (`60.068347, 30.002349`) and the same Web-Mercator `{z}/{x}/{y}` calculation as `UIRenderer.mc`/`MapTileLoader.mc` in the WindSensor project.

## Capabilities

### New Capabilities

- `osm-tile-proxy`: A compliant, stateless-per-tile caching proxy for OpenStreetMap slippy-map tiles: single-tile `GET /tiles/{z}/{x}/{y}.png` endpoint, identifying outbound HTTP headers, EhCache-based local file-system caching with upstream-cache-header-driven TTL (≥7 day fallback), and visible OSM attribution in API documentation.

### Modified Capabilities

(none — `openspec/specs/` is currently empty)

## Impact

- **Code**: new controller + service under `com.vb.wingfoil` (e.g. `TileProxyController`, `OsmTileService`), new test class under `src/test/java`.
- **Configuration**: `src/main/resources/application.yml` gains an `osm-tiles` section (upstream base URL, user-agent, referer, min TTL, ehcache storage path); `dev_setup/docker-compose.yml` gains a volume mapping for the EhCache storage folder.
- **Dependencies**: adds `io.micronaut.cache:micronaut-cache-core` and `io.micronaut.cache:micronaut-cache-ehcache` to `build.gradle` (the project currently has no Gradle version catalog).
- **External systems**: runtime traffic to `tile.openstreetmap.org` now originates from the backend with compliant headers; integration tests hit the real tile source.
- **Non-goals (this change)**: watch-side loader fix, configurable `MAP_TILE_BASE_URL` in the app, on-device attribution text, bulk/bbox tile endpoints — all tracked in other parts of `fix-osm-tile-compliance`.
