package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code osm-tiles.optimization.*} binding end-to-end: valid values bind to the typed mode,
 * and an invalid value fails application startup (the validator is an eager singleton).
 */
class OsmTilesOptimizationBindingTest {

    @Test
    void validValuesBindToTypedMode() {
        try (var ctx = ApplicationContext.builder()
                .deduceEnvironment(false)
                .environments("test")
                .properties(Map.of(
                        "osm-tiles.optimization.enabled", "true",
                        "osm-tiles.optimization.colors", "original"))
                .start()) {
            var optimization = ctx.getBean(OsmTilesConfiguration.class).getOptimization();
            assertTrue(optimization.isEnabled());
            assertEquals(MapTileColorMode.ORIGINAL, optimization.getColorsMode());
        }
    }

    @Test
    void invalidColorsValueFailsStartup() {
        var ex = assertThrows(RuntimeException.class, () -> startWithColors("15"));
        assertTrue(messageChainContains(ex, "15"), "startup error should name the invalid value");
    }

    private void startWithColors(String colors) {
        try (var ignored = ApplicationContext.builder()
                .deduceEnvironment(false)
                .environments("test")
                .properties(Map.of("osm-tiles.optimization.colors", colors))
                .start()) {
            // reaching here means startup did not fail for an invalid value
        }
    }

    private static boolean messageChainContains(Throwable t, String needle) {
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
