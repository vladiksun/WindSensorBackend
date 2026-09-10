## 1. Service layer

- [x] 1.1 Add a `skipCache` boolean parameter to `OsmTileService.getTile(int z, int x, int y, boolean skipCache)`: when `true`, skip the cache-hit short-circuit and go straight to `fetchFromUpstream`; keep reading the previous entry only for its Etag (pass `null` as previous when `skipCache=true` is NOT set — i.e. preserve existing behavior), and always store the fresh result in the cache on success.
- [x] 1.2 Keep the existing 3-arg `getTile(z, x, y)` signature working by delegating to the new overload with `skipCache = false` (or update all call sites if none remain outside the controller).

## 2. Controller layer

- [x] 2.1 Add `@QueryValue(defaultValue = "false") boolean skipCache` to `TileProxyController.getTile` and pass it through to the service.
- [x] 2.2 Update the OpenAPI `@Operation`/`@Parameter` annotations: document `skipCache` (optional, boolean, default `false`) and note that `skipCache=true` forces an upstream fetch while still populating the local cache.

## 3. Tests

- [x] 3.1 Unit test in `OsmTileServiceTest`: with a valid cached entry present, `getTile(..., skipCache = true)` triggers an upstream fetch (assert upstream request made / status is `MISS` or `REVALIDATED`, not `HIT`).
- [x] 3.2 Unit test: after a successful `skipCache = true` fetch, a subsequent normal `getTile(..., false)` for the same tile is served from cache (`HIT`).
- [x] 3.3 Unit test: `getTile(..., skipCache = false)` with a valid cached entry still returns `HIT` without contacting upstream (regression guard for default behavior).
- [x] 3.4 Controller-level test (existing Micronaut test setup): `GET /tiles/{z}/{x}/{y}.png?skipCache=true` binds the parameter correctly; absent parameter defaults to `false`.

## 4. Verification

- [x] 4.1 Run `./gradlew -q spotlessCheck` (apply `spotlessApply` if needed) and compile `compileJava` + `compileTestJava`.
- [x] 4.2 Run targeted tests for `com.vb.wingfoil.tiles` and confirm they pass.
- [x] 4.3 Manually verify against a running instance: `curl 'https://localhost:443/tiles/15/19114/9503.png?skipCache=true' -k -D -` shows `X-Cache: MISS` (or `REVALIDATED`), then a repeat without the parameter shows `X-Cache: HIT`. (Covered by the passing `OsmTileProxyIntegrationTest.skipCacheParameterForcesUpstreamFetch` integration test; no local instance was available for a live curl check.)
