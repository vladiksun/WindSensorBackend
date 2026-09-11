# Proposal: Add configurable OSM map image optimization to the composite pipeline

## Why

The Garmin watch app downloads full 24-bit RGB composite PNGs from the backend. Map imagery is
low-chroma and flat-coloured, so quantizing the **final composite** to a small palette and encoding
it as an indexed (paletted) PNG can shrink the network payload substantially without visible
quality loss — at the cost of only modest extra CPU on the server. This must be opt-in via
backend configuration so existing deployments keep byte-for-byte today's behaviour until they
explicitly enable it.

## What Changes

- New optional optimization stage applied to the final composite image in
  `TileCompositeService`, controlled entirely by backend configuration
  (`osm-tiles.optimization.*`). **No client/request parameter** selects or influences the
  optimization mode; the HTTP API surface is unchanged.
- New typed configuration under the existing `osm-tiles` hierarchy:
  - `enabled` (boolean, default `false`) — when `false`, the existing render + PNG-encode path runs
    untouched (no quantization, no palette conversion, no extra processing).
  - `colors` — enum restricted to `original | 16 | 32 | 64` (default `32`); ignored when
    `enabled=false`. Arbitrary numbers (e.g. `15`, `128`) are rejected.
- New dedicated, framework-independent component (`MapImageOptimizer`) that takes the final
  `BufferedImage` composite and returns PNG bytes for a given color mode. It knows nothing about
  HTTP, OSM, tiles, caching, or the controller layer.
- Real color quantization (median cut) for the 16/32/64 modes — not channel truncation — producing
  a shared palette for the whole viewport, with **dithering disabled** (flat map regions stay flat).
- Indexed/paletted PNG output for the 16/32/64 modes via `IndexColorModel`; `original` mode keeps
  true RGB colors but still goes through the optimizer's encoding path (useful for benchmarking).
- Refactor of the internal boundary in `TileCompositeService`: `render()` will return a
  `BufferedImage` instead of PNG bytes; encoding/optimization happens after composition. Viewport
  math, tile placement, and the failed-tile gray fill are unchanged.
- Composite cache keys gain an optimization-mode segment so different representations of the same
  viewport do not collide (e.g. `composite/{z}/{lat:.6f}/{lon:.6f}/{width}x{height}/off|original|c16|c32|c64`).
- Explicit non-goals / preserved behavior:
  - `OsmTileService` and the original-tile cache are **not modified**: upstream tiles are always
    fetched, cached, and served as original PNGs. Quantization happens once, after all component
    tiles are composed into the full RGB composite.
  - Composites remain opaque (`TYPE_INT_RGB`); no alpha channel is introduced.
  - Failed-tile gray `new Color(192, 192, 192)` is unchanged.
  - No custom PNG encoder; encoding goes through the TwelveMonkeys ImageIO facade backed by the
    standard ImageIO PNG writer (compression level only if it can be configured cleanly;
    sensible defaults otherwise). No EXIF or other metadata is added.

## Capabilities

### New Capabilities

- `tile-composite-optimization`: backend-configurable post-composition image optimization of the
  OSM composite endpoint — config schema/validation, the optimizer component contract, indexed
  paletted PNG output, dithering-off quantization, backward-compatible disabled path, and
  optimization-aware composite cache keys.

### Modified Capabilities

<!-- None: openspec/specs/ contains no baseline capability specs yet; the osm-tile-proxy and
     tile-skip-cache behaviors are explicitly preserved unchanged by this change. -->

## Impact

- **Code**: `src/main/java/com/vb/wingfoil/tiles/`
  - `TileCompositeService` — `render()` boundary refactor, encode-vs-optimize branch, cache-key
    extension.
  - `OsmTilesConfiguration` — new nested `optimization` settings (or a dedicated nested
    configuration class following project conventions).
  - New `MapImageOptimizer` (+ median-cut quantizer helper) in the same package.
- **Config**: `application.yml` gains `osm-tiles.optimization.enabled: false` and
  `osm-tiles.optimization.colors: 32` (safe defaults preserving current output).
- **Dependencies**: adds TwelveMonkeys ImageIO (`com.twelvemonkeys.imageio:imageio-core`,
  3.15.0, BSD) as the image-encoding foundation. TwelveMonkeys provides no color-quantization API
  and no PNG writer plugin (PNG is a JDK format), so quantization remains a small internal
  median-cut over JDK `java.awt.image` APIs (`IndexColorModel`, `BufferedImage`). No
  computer-vision frameworks.
- **API/clients**: none observable — same endpoints, same headers, same request parameters.
  Existing clients (Garmin app) are unaffected unless the operator enables optimization.
- **Caching**: `osm-composites` EhCache entries are keyed per optimization mode; enabling/changing
  optimization produces fresh cache entries rather than serving stale representations. Existing
  `osm-tiles` cache is untouched.
- **Performance**: enabled modes add one quantization pass over the composite (≤1024×1024 px) per
  cache miss; indexed PNGs are smaller on the wire and typically decode faster on the watch.
