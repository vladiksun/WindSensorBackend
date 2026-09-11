# Design: Configurable OSM composite image optimization

## Context

See proposal.md for motivation. Current state that shapes this design:

- `TileCompositeService.compose()` (Micronaut `@Singleton`, package `com.vb.wingfoil.tiles`)
  computes the viewport, fetches tiles in parallel via `OsmTileService`, and calls a private
  `render(...)` that today both composes a `TYPE_INT_RGB` canvas **and** encodes it to PNG bytes
  (`ImageIO.write(image, "png", out)`), then caches those bytes in the `osm-composites` EhCache
  under key `composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}`.
- Configuration lives in `OsmTilesConfiguration` bound to the `osm-tiles` prefix
  (`@ConfigurationProperties`). The prompt's `map.tiles.*` sketch maps onto the existing
  `osm-tiles.*` hierarchy per project conventions.
- No third-party image library is currently on the classpath (only JDK `javax.imageio` /
  `java.awt.image`); this change adds TwelveMonkeys ImageIO `imageio-core` (see D3). Viewports are
  capped at `MAX_DIMENSION = 1024`, so composites are ≤ 1024×1024 px (~4 MB as an int buffer).
- Tests already exercise headless AWT + ImageIO round-trips.

## Goals / Non-Goals

**Goals:**

- Opt-in (config-only) quantization of the final composite with indexed-PNG output; byte-for-byte
  unchanged behavior when disabled.
- Clean internal boundary: composition produces a `BufferedImage`; encoding/optimization is a
  separate step.
- Deterministic, dither-free, metadata-free output; mode-aware composite cache keys.

**Non-Goals:**

- Any change to `OsmTileService`, tile caching, upstream fetching, or the HTTP API surface.
- Per-request/per-client optimization selection. Alpha channels. Custom PNG encoder.
- Optimizing single-tile proxy responses (only composites are optimized).

## Decisions

### D1: Configuration shape — nested typed properties under `osm-tiles.optimization`

Add to `OsmTilesConfiguration`:

```java
private Optimization optimization = new Optimization();

public static class Optimization {
    private boolean enabled = false;
    private MapTileColorMode colors = MapTileColorMode.COLORS_32;
    // getters/setters
}
```

with a new enum in the same package:

```java
public enum MapTileColorMode { ORIGINAL, COLORS_16, COLORS_32, COLORS_64 }
```

- Micronaut binds enums by name case-insensitively, but the required YAML syntax uses `original`
  and bare numbers (`16/32/64`), which do not match enum names. **Decision:** bind the property as
  `String` at the config edge only, and expose a validated typed accessor
  `getColorsMode(): MapTileColorMode` backed by `MapTileColorMode.fromConfigValue(String)`
  (accepts `original|16|32|64`, case-insensitive; anything else throws during context refresh,
  failing startup). This keeps the required YAML syntax while giving the rest of the app a typed
  value. Alternative considered: a custom `PropertyConverter<String, MapTileColorMode>` — equally
  valid; pick whichever integrates more cleanly with the existing tests during implementation.
  Either way, invalid values MUST fail startup (spec requirement).
- Defaults (`enabled=false`, `colors=32`) make upgrades behavior-preserving.
- No `dithering` config key: dithering is always off (the prompt allows omitting unnecessary
  configuration).

**Rationale:** matches the requested YAML exactly, reuses the existing config class, and keeps raw
strings confined to the binding boundary.

### D2: `render()` returns `BufferedImage`; encode/optimize branch in `compose()`

Refactor per the prompt:

```java
BufferedImage image = render(viewport, tileRefs, fetched, width, height); // no longer encodes
byte[] png;
if (!config.getOptimization().isEnabled()) {
    png = encodePng(image);            // existing ImageIO path, untouched
} else {
    png = optimizer.optimize(image, config.getOptimization().getColorsMode());
}
```

- `encodePng(BufferedImage)` stays as-is for the disabled path (byte-compatible with today).
- `render()` body is otherwise identical (same canvas type, gray pre-fill, draw order).
- The optimizer is injected as a constructor dependency of `TileCompositeService`.

**Alternatives:** route everything through the optimizer (including disabled) — rejected because
the disabled path must stay as close to today's code as possible.

### D3: `MapImageOptimizer` — dedicated component backed by TwelveMonkeys ImageIO

New `@Singleton MapImageOptimizer` in `com.vb.wingfoil.tiles`:

```java
public byte[] optimize(BufferedImage rgb, MapTileColorMode mode)
```

- Input contract: opaque `TYPE_INT_RGB` composite. Output: PNG bytes.
- `ORIGINAL`: encode the RGB image directly (no palette).
- `COLORS_16/32/64`: median-cut quantize → build `IndexColorModel(8, n, r, g, b)` → fill a
  `TYPE_BYTE_INDEXED` raster with palette indices → encode (PLTE chunk, color type 3).
- **Encoding via TwelveMonkeys ImageIO** (new dependency
  `com.twelvemonkeys.imageio:imageio-core:3.15.0`, added to `build.gradle` following the
  project's existing coordinate convention): the optimizer encodes through the
  `com.twelvemonkeys.imageio.ImageIO` facade, which wraps the standard ImageIO pipeline with
  cleaner exception semantics (`ImageWriteException` instead of raw `IOException`) and provides
  the TwelveMonkeys metadata framework. For PNG specifically, TwelveMonkeys ships no separate
  writer plugin (PNG is a JDK format), so the actual encoder remains the JDK `PngImageWriter`
  selected through the same SPI machinery — while gaining TwelveMonkeys' robustness and a
  consistent foundation for any future format or metadata needs.
- Depends on `java.awt.image`, `javax.imageio`, and TwelveMonkeys core only — no HTTP, OSM,
  coordinates, or caching.
- Metadata: the stock PNG writer emits only IHDR/PLTE/IDAT/IEND (no tEXt/EXIF), satisfying the
  metadata-free requirement without extra work. Compression level left at the writer default
  (deflate ~6); `PngImageWriteParam` exposes it cleanly if benchmarks later demand a knob.

**Alternatives considered:**

- *JDK ImageIO only (no dependency)* — the original choice; superseded by the decision to
  standardize on TwelveMonkeys ImageIO as the image-processing foundation.
- *TwelveMonkeys for quantization* — not possible: the library provides format plugins and core
  utilities but no color-quantization API (verified against the 3.15.0 module/class inventory),
  so quantization stays the small internal median cut from D4.
- *Apache Commons Imaging* — has quantizers but pulls a large, partially-maintained dependency
  and its API is awkward for indexed output.
- *k-means* — non-deterministic without fixed seeding/iterations; median cut is simpler and fully
  deterministic.

### D4: Median-cut quantizer (small, internal helper)

Deterministic median cut over the composite's distinct colors:

1. Collect unique ARGB ints from the canvas into a list (≤ w·h entries; for flat map imagery the
   distinct-color count is far smaller than pixel count).
2. If distinct count ≤ N, use them all (palette may be smaller than N — spec allows ≤ N).
3. Otherwise repeatedly split the bucket with the largest range along its widest channel at the
   median (stable ordering: sort by channel value; ties broken deterministically by full int
   value), until there are N buckets.
4. Bucket mean color = palette entry. Map every pixel to its nearest palette entry by squared
   Euclidean distance in RGB (exact, no truncation); ties broken by lower index.

No error diffusion anywhere (flat regions map to one entry). Complexity is fine at ≤1024² px.

### D5: Mode-aware composite cache key

Extend `compositeKey` with a trailing mode segment derived from the effective configuration:

```text
composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/off        (enabled=false)
composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/original  (enabled=true, original)
composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/c16
composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/c32
composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/c64
```

The key builder takes the effective mode (not raw config), so `enabled=false` always yields
`/off` regardless of `colors`. Existing disk-cache entries (old key format) simply miss after
deploy and are rebuilt once — acceptable, documented in Migration.

### D6: No request-level changes

The composite controller/endpoint is untouched; no new query parameters. Unknown parameters are
already ignored by the framework, satisfying the "client cannot influence" scenario.

## Risks / Trade-offs

- [One-time composite cache miss after deploy] → harmless: first request per viewport rebuilds;
  tile cache still warm.
- [Median-cut quality on photographic-looking zoom levels] → acceptable for watch display;
  `original` mode exists as an escape hatch/benchmark; modes are trivially switchable via config.
- [Extra CPU per composite cache miss in enabled modes] → bounded (≤1024² px, one pass); only
  happens on misses; mitigated by keeping the composite TTL/caching intact.
- [Enum-vs-string config binding subtlety] → covered by a dedicated unit test asserting valid
  values bind and invalid values fail startup (see tasks).
- [Indexed PNG decode support on the Garmin firmware] → paletted PNG is a core PNG feature;
  verify on-device during rollout (open question).
- [New third-party dependency (TwelveMonkeys, BSD)] → small, mature, widely used;
  `imageio-core` pulls only its lightweight `common-*` companions. Plugins self-register via
  `META-INF/services`; no impact on the current JVM (jib/Docker) deployment. If GraalVM native
  images are ever enabled, TwelveMonkeys would need standard reflection/resource configuration
  (the project currently ships JVM-only via jib).

## Migration Plan

1. Deploy with defaults (`enabled: false`) — behavior identical to today except composite cache
   keys gain the `/off` suffix (one-time rebuild of composites).
2. Operator enables optimization by setting `osm-tiles.optimization.enabled: true` (+ optional
   `colors`) and restarting. New-mode cache entries accumulate alongside old ones.
3. Rollback: set `enabled: false` and restart; the `/off` path is the legacy pipeline. Old cache
   entries remain valid for their respective modes; nothing needs purging.

## Open Questions

- Should the PNG compression level become a config knob later? Deferred — measure first.
- On-device verification of indexed-PNG decoding speed on the target Garmin model (post-deploy).
