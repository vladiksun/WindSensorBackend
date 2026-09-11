package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Asserts the bound defaults match the values declared in src/main/resources/application.yml. */
@MicronautTest
class OsmTilesConfigurationTest {

    private final OsmTilesConfiguration config;

    OsmTilesConfigurationTest(OsmTilesConfiguration config) {
        this.config = config;
    }

    @Test
    void defaultsMatchApplicationYml() {
        assertEquals("https://tile.openstreetmap.org", config.getBaseUrl());
        assertEquals(
                "WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)",
                config.getUserAgent());
        assertEquals("https://github.com/vladiksun/WindSensor", config.getReferer());
        assertEquals(Duration.ofDays(7), config.getMinTtl());
        assertEquals("/tmp/windsensorbackend/ehcache", config.getEhcacheStoragePath());

        // Optimization defaults: disabled, so existing output is byte-for-byte unchanged.
        var optimization = config.getOptimization();
        assertFalse(optimization.isEnabled(), "optimization must be disabled by default");
        assertEquals(MapTileColorMode.COLORS_32, optimization.getColorsMode(), "default palette is 32");
    }
}
