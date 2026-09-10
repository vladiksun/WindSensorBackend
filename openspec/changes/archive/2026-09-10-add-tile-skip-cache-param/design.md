## Context

The tile proxy (`com.vb.wingfoil.tiles`) serves OSM tiles through `TileProxyController.getTile`, which delegates to `OsmTileService.getTile(z, x, y)`. The service first looks up the EhCache-backed `osm-tiles` cache; a valid entry short-circuits the upstream fetch and is returned with `X-Cache: HIT`. See proposal.md for motivation.

Constraints:
- The endpoint stays a single GET route `/tiles/{z}/{x}/{y}.png`; no new routes.
- Existing clients must keep identical behavior when they do not send the parameter.
- The project uses Micronaut 5, Vavr `Try`, Java records, and OpenAPI annotations on the controller.

## Goals / Non-Goals

**Goals:**
- Add an optional `skipCache` boolean query parameter (default `false`) to the tile endpoint.
- With `skipCache=true`, bypass the cache *read* so the tile always comes from upstream, while keeping the cache *write* so later normal requests benefit.
- Document the parameter in the OpenAPI operation.

**Non-Goals:**
- No way to invalidate or purge cached entries.
- No per-client caching policy changes (response `Cache-Control` semantics unchanged).
- No configuration flag — this is purely a request-level opt-in.

## Decisions

### 1. Query parameter, not a header or separate route

`@QueryValue(defaultValue = "false") boolean skipCache` on the existing handler. A query parameter matches the requested call example (`.../9503.png?skipCache=true`), is trivially visible in logs and browser devtools, and avoids adding a second route. Alternatives considered: a custom header (less discoverable, awkward in `<img>` tags used by map libraries) and a parallel route like `/tiles/fresh/{z}/{x}/{y}.png` (duplicates the route table and complicates the "single tile-serving route" spec constraint).

### 2. Skip the read, keep the write

When `skipCache=true`, `OsmTileService.getTile` skips the `tileCache.get(...)` short-circuit and goes straight to `fetchFromUpstream`, but still stores the fresh result in the cache. This makes `skipCache=true` idempotent and useful: one forced refresh warms the cache for everyone. Alternative considered: also skipping the write (pure pass-through) — rejected because it would leave stale bytes in the cache after a forced refresh, defeating the purpose.

### 3. Etag handling on forced refetch

Even with `skipCache=true`, the previous cached entry's Etag is still passed to the conditional request (`If-None-Match`). Rationale: if upstream answers `304 Not Modified`, the response is still authoritative-fresh (upstream confirmed currentness) and we avoid re-downloading the body. The `X-Cache` status will report `REVALIDATED` in that case, which is accurate. If callers need byte-for-byte unconditional downloads, that can be revisited later without a spec change.

### 4. Parameter validation

Micronaut binds `true`/`false` case-insensitively; any other value yields a 400 via standard binding failure. No custom validation needed.

## Risks / Trade-offs

- [Abuse: clients hammering `skipCache=true` bypasses the cache and hammers upstream] → Mitigation: this is an explicit client opt-in intended for debugging/refresh use; OSM usage-policy compliance already relies on well-behaved clients. Rate limiting is tracked separately (see memory bank next steps).
- [Slightly higher upstream load during map data updates] → Inherent to the feature; each forced fetch replaces the cache entry, so repeated `skipCache=true` calls are bounded by client polling frequency.
- [`REVALIDATED` status on a forced fetch may surprise consumers expecting `MISS`] → Documented in the OpenAPI description; `X-Cache` values remain one of the existing three statuses.
