# Tasks: Add OSM Caching Tile Proxy

## 1. Dependencies & configuration

- [x] 1.1 Add `io.micronaut.cache:micronaut-cache-core` and `io.micronaut.cache:micronaut-cache-ehcache` to `build.gradle` (implementation scope); verify `./gradlew -q dependencies --configuration compileClasspath | grep micronaut-cache` lists both artifacts.
- [x] 1.2 Add the `osm-tiles` section (`base-url`, `user-agent`, `referer`, `min-ttl`, `ehcache-storage-path`) and the `ehcache` section (`storage-path`, `caches.osm-tiles` with heap + disk tiers) to `src/main/resources/application.yml`, all env-overridable per design D3; verify `https://tile.openstreetmap.org` appears as the default `base-url` in `src/main/resources/application.yml` and the app boots with `./gradlew run`.
- [x] 1.3 Create `com.vb.wingfoil.tiles.OsmTilesConfiguration` bound to `osm-tiles.*` (record or @ConfigurationProperties class); verify a unit test asserts the defaults match the values in `application.yml`.

## 2. Cache entry & upstream fetch service

- [x] 2.1 Add serializable record `CachedTile(byte[] png, long fetchedAtEpochMillis, long expiresAtEpochMillis, String etag)` under `com.vb.wingfoil.tiles`; verify it compiles and round-trips through Java serialization in a unit test.
- [x] 2.2 Implement `OsmTileService.getTile(int z, int x, int y)` using Micronaut Cache's `SyncCache<String, CachedTile>` named `osm-tiles`: hit-within-validity returns cached bytes with `X-Cache: HIT`; miss/expired fetches upstream via Apache HttpClient5 with configured `User-Agent`, `Referer` (and `If-None-Match` when an Etag is stored), parses `Cache-Control`/`Expires` for expiry with the ≥7-day fallback floor, stores the entry, and never sends `no-cache` upstream; non-2xx/IO errors surface as a 502 without caching; verify with unit tests against a local stub HTTP server covering 200-store, 304-revalidate, 404-no-store, and missing-cache-headers-fallback cases.
- [x] 2.3 Extract outbound header construction into a small helper (e.g. `TileUpstreamRequestFactory`) that applies the configured `User-Agent`/`Referer` explicitly on each request; verify a unit test asserts the exact header values and that no library-default User-Agent can be emitted.

## 3. Controller & attribution

- [x] 3.1 Implement `TileProxyController` with route `GET /tiles/{z}/{x}/{y}.png` returning `HttpResponse<byte[]>` (`Content-Type: image/png`, `X-Cache` header, `Cache-Control` reflecting remaining validity) and validating `z ∈ [0..20]`, `x,y ∈ [0..2^z-1]` → 400 otherwise; verify `curl -i {serverUrl}/tiles/15/19114/9503.png` returns HTTP 200 with a valid PNG body and invalid coordinates return 400.
- [x] 3.2 Add OpenAPI `@Operation` description to the tile route containing "© OpenStreetMap contributors" and the `openstreetmap.org/copyright` reference; verify the generated spec at `/swagger` includes both strings for the tile operation.
- [x] 3.3 Confirm no bulk/bbox/multi-tile routes exist (only the single-tile route serves tiles); verify by inspecting the generated route list/OpenAPI paths.

## 4. Docker compose volume

- [x] 4.1 Map the EhCache storage folder in `dev_setup/docker-compose.yml` (host dir → `${EHCACHE_STORAGE_PATH}` default path used by the container) and pass the matching environment variable; verify `docker compose config` renders the volume mount and the running container keeps cached tiles across `docker restart windsensorbackend`.

## 5. Integration test (real tile generation)

- [x] 5.1 Add `OsmTileProxyIntegrationTest` (JUnit 5 + `@MicronautTest` + RestAssured) that replicates the watch app's Web-Mercator `{z}/{x}/{y}` calculation from `MapTileLoader.computeTiles` for `FIXED_GPS_COORDINATES = [60.068347, 30.002349]` at zoom 15 and asserts the centre tile is `15/19114/9503`; verify `./gradlew :test --tests 'com.vb.wingfoil.tiles.OsmTileProxyIntegrationTest'` passes the coordinate-math assertion.
- [x] 5.2 In the same test, request the real tile through the proxy with `ehcache.storage-path` pointed at a JUnit `@TempDir` and SSL disabled: assert first response is 200 + PNG magic bytes + `X-Cache: MISS`, second response has identical bytes + `X-Cache: HIT`; verify the full test passes via `./gradlew :test --tests 'com.vb.wingfoil.tiles.OsmTileProxyIntegrationTest'` (requires internet access to `tile.openstreetmap.org`).

## 6. Final validation

- [x] 6.1 Run `./gradlew -q spotlessCheck` (apply `spotlessApply` if needed), `./gradlew -q compileJava`, `./gradlew -q compileTestJava`, and the targeted tile tests; verify all pass.
- [x] 6.2 End-to-end compliance check: start the app, capture one upstream fetch (logs) and confirm (a) clean `{base}/{z}/{x}/{y}.png` URL, (b) configured identifying User-Agent + Referer present, (c) repeat request served from cache within the ≥7-day window, (d) no `no-cache` sent upstream; verify each holds.
