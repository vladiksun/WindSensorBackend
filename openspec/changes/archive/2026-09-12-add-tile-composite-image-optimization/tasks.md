## 1. Dependencies & Configuration

- [ ] 1.1 Add `com.twelvemonkeys.imageio:imageio-core:3.15.0` to `build.gradle` (implementation
      scope, following the project's existing coordinate convention) and verify the application
      boots and existing PNG round-trip tests still pass
- [ ] 1.2 Add `MapTileColorMode` enum (`ORIGINAL`, `COLORS_16`, `COLORS_32`, `COLORS_64`) with a
      `fromConfigValue(String)` parser accepting `original|16|32|64` (case-insensitive) and a
      `paletteSize()` accessor; throw on any other value
- [ ] 1.3 Extend `OsmTilesConfiguration` with nested `optimization` settings (`enabled`, default
      `false`; `colors`, default `32`) bound from `osm-tiles.optimization.*`, exposing a typed
      `getColorsMode(): MapTileColorMode` so invalid values fail application startup
- [ ] 1.4 Add `osm-tiles.optimization.enabled: false` and `osm-tiles.optimization.colors: 32` to
      `application.yml` with comments explaining the modes
- [ ] 1.5 Unit tests: valid values bind to the expected enum; defaults apply when absent; each
      invalid value (`15`, `17`, `128`, `256`, garbage) fails context startup with a clear error

## 2. Optimizer component

- [ ] 2.1 Implement the deterministic median-cut quantizer helper per design D4 (distinct-color
      collection, widest-channel median splits, bucket-mean palette, nearest-entry pixel mapping,
      no dithering, stable tie-breaking)
- [ ] 2.2 Implement `@Singleton MapImageOptimizer.optimize(BufferedImage, MapTileColorMode)`:
      `ORIGINAL` encodes RGB directly; `COLORS_16/32/64` build an `IndexColorModel`, fill a
      `TYPE_BYTE_INDEXED` raster, and encode through the TwelveMonkeys
      `com.twelvemonkeys.imageio.ImageIO` facade (delegates to the JDK PNG writer)
- [ ] 2.3 Unit tests for the optimizer: palette size ≤ N for each mode; decoded PNG is indexed
      color type with no alpha; flat input regions map to a single solid palette entry (no
      dither noise); identical input + mode yields byte-identical output twice; `ORIGINAL` keeps
      true RGB colors; output contains no EXIF/tEXt metadata chunks
- [ ] 2.4 Unit test: quantization is real (a synthetic multi-color image does not degrade to
      channel-truncated colors — e.g. distinct source colors map to nearest mean entries)

## 3. Composite pipeline refactor

- [ ] 3.1 Refactor `TileCompositeService.render(...)` to return `BufferedImage` (composition only),
      keeping canvas type, gray pre-fill, and tile placement unchanged
- [ ] 3.2 Branch in `compose()`: disabled → existing `encodePng(image)` path; enabled →
      `optimizer.optimize(image, config.getOptimization().getColorsMode())`; inject the optimizer
      into `TileCompositeService`
- [ ] 3.3 Extend `compositeKey` with the effective-mode segment (`off|original|c16|c32|c64`) per
      design D5; `enabled=false` always maps to `off` regardless of `colors`
- [ ] 3.4 Update/add unit tests: disabled path produces the same PNG bytes as the pre-change
      encoder for the same composite; failed-tile gray (192,192,192) preserved; cache key differs
      per mode and reuses within a mode; tile cache still holds original upstream bytes after an
      optimized compose

## 4. End-to-end verification

- [ ] 4.1 Endpoint test: with optimization disabled, response is unchanged 24-bit RGB PNG and
      unknown query params (e.g. `colors=16`) have no effect
- [ ] 4.2 Endpoint test: with `enabled=true, colors=32`, response decodes to an indexed PNG with
      ≤32 palette entries; switching modes serves fresh composites (no cross-mode cache hits)
- [ ] 4.3 Run full module test suite, `spotlessCheck` (apply if needed), and compile checks per
      the project workflow
