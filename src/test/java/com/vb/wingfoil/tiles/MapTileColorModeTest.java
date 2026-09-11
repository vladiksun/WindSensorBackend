package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MapTileColorMode#fromConfigValue(String)} parsing and palette sizes. */
class MapTileColorModeTest {

    @Test
    void validValuesParseCaseInsensitively() {
        var cases = Map.of(
                "original", MapTileColorMode.ORIGINAL,
                "ORIGINAL", MapTileColorMode.ORIGINAL,
                " Original ", MapTileColorMode.ORIGINAL,
                "16", MapTileColorMode.COLORS_16,
                "32", MapTileColorMode.COLORS_32,
                "64", MapTileColorMode.COLORS_64);
        cases.forEach((raw, expected) -> assertEquals(expected, MapTileColorMode.fromConfigValue(raw), raw));
    }

    @Test
    void paletteSizes() {
        assertEquals(0, MapTileColorMode.ORIGINAL.paletteSize());
        assertEquals(16, MapTileColorMode.COLORS_16.paletteSize());
        assertEquals(32, MapTileColorMode.COLORS_32.paletteSize());
        assertEquals(64, MapTileColorMode.COLORS_64.paletteSize());
    }

    @Test
    void invalidValuesAreRejected() {
        for (var raw : new String[] {"15", "17", "128", "256", "garbage", "", "  ", "33"}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> MapTileColorMode.fromConfigValue(raw));
            if (!raw.isBlank()) {
                // The error must name the offending value so operators can fix the setting.
                assertTrue(ex.getMessage().contains(raw.trim()), ex.getMessage());
            }
        }
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MapTileColorMode.fromConfigValue(null));
    }
}
