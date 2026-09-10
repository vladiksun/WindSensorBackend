# Design: OSM Caching Tile Proxy

## Context

WindSensorBackend is a stateless Micronaut 5 / Java 25 REST service (see `memory-bank/systemPatterns.md`). It currently has no cache layer and no binary-serving endpoint. The watch app (separate repo) fetches OSM tiles directly, which violates OSM's tile usage policy; this change adds the backend half of the fix — a caching proxy behind `serverUrl`. Existing conventions: Apache HttpClient5 for outbound HTTP, Vavr `Try` for error handling, configuration externalized to `application.yml` with env-var overrides, Spotless (Palantir format), JUnit 5 + Micronaut Test RestAssured for tests.

Upstream reference facts (verified against docs):
- OSMF Attribution Guidelines: attribution "© OpenStreetMap contributors" + `openstreetmap.org/copyright`, placed in the vicinity of the map/data.
- Micronaut Cache 5.1 EhCache support: `micronaut-cache-core` + `micronaut-cache-ehcache`; per-cache config under `ehcache.caches.<name>` (`heap.max-entries` / `disk.max-size` tiers); `ehcache.storage-path` sets the disk location. **The micronaut-cache EhCache configuration does NOT expose TTL/time-to-live properties** — expiry must be managed by the application.

## Goals / Non-Goals

**Goals:**
- Compliant single-tile proxy route `GET /tiles/{z}/{x}/{y}.png` → upstream `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, `image/png` responses.
- Identifying `User-Agent` + `Referer` on every upstream request, configured, verifiable in tests.
- File-system EhCache cache keyed by `z/x/y` with validity derived from upstream `Cache-Control`/`Expires`/`Etag`, ≥7-day fallback TTL.
- Config in `application.yml`; storage dir volume-mapped in `dev_setup/docker-compose.yml`.
- Real-network integration test using the watch app's fixed GPS coordinate and tile math.

**Non-Goals:**
- Watch-side changes (parts 2–4 of `fix-osm-tile-compliance` live in the WindSensor repo).
- Bulk/bbox tile endpoints, prefetching, offline packs.
- Replacing the stateless architecture elsewhere in the service (cache is scoped to tiles only).
- Auth/rate-limiting on the tile route (out of scope for this change).

## Decisions

### D1. Application-managed TTL inside an EhCache entry (no annotation-only caching)

Micronaut Cache's `@Cacheable` caches method return values with a fixed key and no per-entry TTL (the EhCache provider exposes no TTL property). So the service stores a small serializable record per tile:

```java
record CachedTile(byte[] png, long fetchedAtEpochMillis, long expiresAtEpochMillis, String etag) implements Serializable {}
```

Flow in `OsmTileService.getTile(z, x, y)`:
1. Look up `SyncCache<String, CachedTile>` named `osm-tiles` with key `"{z}/{x}/{y}"`.
2. Hit and `expiresAt > now` → return bytes, respond `X-Cache: HIT`.
3. Miss or expired → fetch upstream (with stored `etag` as `If-None-Match` when present):
   - `200` → parse `Cache-Control`/`Expires` into `expiresAt` (fallback: `now + 7d`), store new entry, `X-Cache: MISS`.
   - `304` → keep old bytes, refresh `expiresAt`, re-store, `X-Cache: REVALIDATED`.
   - non-2xx / IO error → propagate as 502, store nothing.

Alternatives considered:
- *Pure `@Cacheable` on the controller* — rejected: cannot express per-entry dynamic TTL or conditional refetch.
- *Caffeine* — rejected: requirement mandates EhCache file-system persistence.
- *HTTP response `Cache-Control` passthrough only* — insufficient: clients are not guaranteed to honour it; server-side caching is the compliance mechanism.

Expiry parsing: honour `max-age` (and `s-maxage`) if present; else `Expires` header; else fall back to `minTtl` (default 7 days, configurable). If the computed validity is shorter than `minTtl`, use `minTtl` (OSM policy floor). Never send `Cache-Control: no-cache` upstream.

### D2. Upstream calls via existing Apache HttpClient5 stack

Reuse the project's `httpclient5` dependency (already used by wind providers) rather than adding Micronaut `HttpClient` just for this path: build a dedicated `CloseableHttpClient` bean for the tile source with the configured `User-Agent`/`Referer` defaults applied per-request explicitly (never relying on client defaults). Response body read fully into `byte[]` (tiles are ≤ ~300 KB). Errors wrapped in Vavr `Try` consistent with provider code.

Outbound headers (from config):
- `User-Agent: WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)` — email is an assumption, overridable via `OSM_TILES_USER_AGENT` env var.
- `Referer: https://github.com/vladiksun/WindSensor` — overridable via `OSM_TILES_REFERER`.

### D3. EhCache wiring

Dependencies added to `build.gradle` (project has no version catalog today):
- `io.micronaut.cache:micronaut-cache-core`
- `io.micronaut.cache:micronaut-cache-ehcache`

`application.yml`:
```yaml
osm-tiles:
  base-url: ${OSM_TILES_BASE_URL:`https://tile.openstreetmap.org`}
  user-agent: ${OSM_TILES_USER_AGENT:`WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)`}
  referer: ${OSM_TILES_REFERER:`https://github.com/vladiksun/WindSensor`}
  min-ttl: 7d
  ehcache-storage-path: ${EHCACHE_STORAGE_PATH:`/var/lib/windsensorbackend/ehcache`}

ehcache:
  storage-path: ${EHCACHE_STORAGE_PATH:`/var/lib/windsensorbackend/ehcache`}
  caches:
    osm-tiles:
      enabled: true
      heap:
        max-entries: 100
      disk:
        max-size: 1Gb
```

Disk tier ⇒ file-system persistence under `storage-path`; heap tier keeps hot tiles fast. Keys/values are `Serializable` (String key, `CachedTile` record value) matching the provider's default types.

Alternative considered: custom `CacheManagerFactory` reading a full EhCache XML — rejected as unnecessary; the declarative `ehcache.*` properties cover all needs.

### D4. Controller shape & attribution

`TileProxyController` (`@Controller("/tiles")`):
- `GET /{z}/{x}/{y}.png` → `HttpResponse<byte[]>` with `Content-Type: image/png`, `X-Cache` header (`HIT`/`MISS`/`REVALIDATED`), and pass-through-friendly `Cache-Control` reflecting the remaining validity.
- `@Operation`/OpenAPI description carries: *"© OpenStreetMap contributors. Data © OpenStreetMap contributors, CC-BY-SA. See https://www.openstreetmap.org/copyright."* — this satisfies the visible-attribution requirement at the API level; the on-device map attribution is delivered by the watch app (part 3).
- Validation: `z ∈ [0..20]`, `x,y ∈ [0..2^z-1]` → 400 otherwise (prevents nonsensical upstream requests). No other tile routes exist (spec: no bulk endpoints).

### D5. Integration test strategy

New test `src/test/java/com/vb/wingfoil/tiles/OsmTileProxyIntegrationTest.java` (JUnit 5 + `@MicronautTest` + RestAssured, same infra as planned for other tests):
- Replicates `MapTileLoader.computeTiles` Web-Mercator math in Java for `FIXED_GPS_COORDINATES = [60.068347, 30.002349]`, zoom 15 (centre tile = `15/19114/9503`, verified by running the exact formula); asserts the computed centre tile equals that literal so drift in the formula is caught.
- Requests the real tile through the running app context: asserts 200, PNG magic bytes, `X-Cache: MISS` (fresh temp cache dir per test run), then a second request asserting identical bytes + `X-Cache: HIT`.
- Uses `@Property` overrides: `ehcache.storage-path` → JUnit `@TempDir`, SSL disabled, so the test never touches the dev container's volume and stays hermetic except for the one upstream call pair (per requirement: real tile generation).
- Header verification without hitting the network twice: unit-level check that the configured `User-Agent`/`Referer` are exactly what the client builder applies (test double / direct assertion on the request-config helper), plus log-level INFO of outbound headers on each upstream fetch.

## Risks / Trade-offs

- [Real-network integration test can be flaky/slow or blocked in CI] → Test asserts on behaviour, not exact bytes; document that it requires internet; keep it in the main `test` task but make failure diagnostics clear (upstream status logged).
- [Contact email in User-Agent is an assumption] → Externalized via `OSM_TILES_USER_AGENT` env var; one-line fix if the owner prefers a different address.
- [EhCache disk writes inside the JVM container need a writable volume] → Default path `/var/lib/windsensorbackend/ehcache` is volume-mounted in compose; local `./gradlew run` falls back gracefully (directory created under the working tree if the default path is not writable — verified during implementation).
- [Stale tiles up to 7 days when upstream omits cache headers] → Acceptable per OSM policy floor; upstream currently sends explicit cache headers, so the fallback rarely applies.
- [AOT: new beans/annotations must survive AOT processing] → `optimizeServiceLoading=false` already set; verify with a compiled AOT run during implementation if time permits (not blocking).

## Migration Plan

1. Deploy new image; old clients unaffected (new route only).
2. Watch app switches `MAP_TILE_BASE_URL` to `{serverUrl}/tiles` (separate repo change, part 3).
3. Rollback: remove the route/image; cache volume is disposable data.

## Open Questions

- Preferred contact email for the User-Agent (default assumed `vladiksun@gmail.com`, overridable via env).

