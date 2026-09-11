package com.vb.wingfoil.tiles;

import java.util.Locale;

/**
 * Post-composition color mode for OSM composite images. {@link #ORIGINAL} keeps the composite's true
 * RGB colors; the {@code COLORS_*} modes reduce it to a palette of at most the given size. Values are
 * bound from the {@code osm-tiles.optimization.colors} configuration property, which accepts the
 * literals {@code original | 16 | 32 | 64}.
 */
public enum MapTileColorMode {

    /** Keep the composite's original 24-bit RGB colors (no palette reduction). */
    ORIGINAL(0),

    /** Reduce the composite to a palette of at most 16 colors. */
    COLORS_16(16),

    /** Reduce the composite to a palette of at most 32 colors. */
    COLORS_32(32),

    /** Reduce the composite to a palette of at most 64 colors. */
    COLORS_64(64);

    private final int paletteSize;

    MapTileColorMode(int paletteSize) {
        this.paletteSize = paletteSize;
    }

    /** Maximum number of palette entries for this mode (0 for {@link #ORIGINAL}). */
    public int paletteSize() {
        return paletteSize;
    }

    /**
     * Parses a configuration value into a color mode. Accepts {@code original}, {@code 16},
     * {@code 32} and {@code 64} case-insensitively (surrounding whitespace ignored).
     *
     * @throws IllegalArgumentException when the value is not one of the supported literals
     */
    public static MapTileColorMode fromConfigValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("osm-tiles.optimization.colors must not be empty");
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "original" -> ORIGINAL;
            case "16" -> COLORS_16;
            case "32" -> COLORS_32;
            case "64" -> COLORS_64;
            default ->
                throw new IllegalArgumentException(
                        "Invalid osm-tiles.optimization.colors value '" + value + "' (expected original|16|32|64)");
        };
    }
}
