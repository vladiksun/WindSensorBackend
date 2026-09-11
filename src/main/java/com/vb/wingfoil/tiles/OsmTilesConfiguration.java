package com.vb.wingfoil.tiles;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;
import java.time.Duration;

/**
 * Configuration for the OpenStreetMap caching tile proxy ({@code osm-tiles.*} in
 * {@code application.yml}). The identifying User-Agent and Referer are mandatory per OSM's tile
 * usage policy and must never fall back to library defaults.
 */
@ConfigurationProperties("osm-tiles")
public class OsmTilesConfiguration {

    private String baseUrl;

    private String userAgent;

    private String referer;

    private Duration minTtl;

    /**
     * File-system location of the tile cache. Mirrors {@code ehcache.storage-path} (both are driven
     * by the same {@code EHCACHE_STORAGE_PATH} env var); kept here so the tile proxy's persistence
     * location is visible alongside its other settings.
     */
    private String ehcacheStoragePath;

    /**
     * {@code osm-tiles.optimization.enabled}. Bound directly via {@link Property} rather than as a
     * nested-POJO property: Micronaut does not apply nested-object configuration binding to the
     * {@link #getOptimization()} view, so the leaf values are injected here and assembled on read.
     */
    @Property(name = "osm-tiles.optimization.enabled", defaultValue = "false")
    private boolean optimizationEnabled;

    /** {@code osm-tiles.optimization.colors} — see {@link #optimizationEnabled} for why it is bound directly. */
    @Property(name = "osm-tiles.optimization.colors", defaultValue = "32")
    private String optimizationColors;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    /** Minimum validity window applied when upstream cache headers are absent or unreadable. */
    public Duration getMinTtl() {
        return minTtl;
    }

    public void setMinTtl(Duration minTtl) {
        this.minTtl = minTtl;
    }

    public String getEhcacheStoragePath() {
        return ehcacheStoragePath;
    }

    public void setEhcacheStoragePath(String ehcacheStoragePath) {
        this.ehcacheStoragePath = ehcacheStoragePath;
    }

    /**
     * Post-composition image optimization settings ({@code osm-tiles.optimization.*}). Assembled on
     * read from the directly-bound leaf properties so callers keep a single typed accessor.
     */
    public Optimization getOptimization() {
        var optimization = new Optimization();
        optimization.setEnabled(optimizationEnabled);
        optimization.setColors(optimizationColors);
        return optimization;
    }

    /**
     * Post-composition image optimization settings ({@code osm-tiles.optimization.*}). When
     * {@code enabled} is {@code false} the composite pipeline runs its legacy render-and-encode path
     * untouched and the color mode is ignored. The raw {@code colors} value is bound as a string at
     * the configuration edge; {@link #getColorsMode()} exposes it as a typed {@link MapTileColorMode},
     * throwing on any out-of-set value so an invalid setting fails application startup (see
     * {@link OsmTilesOptimizationValidator}).
     */
    public static class Optimization {

        private boolean enabled = false;

        private String colors = "32";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getColors() {
            return colors;
        }

        public void setColors(String colors) {
            this.colors = colors;
        }

        /**
         * The configured color mode as a typed value.
         *
         * @throws IllegalArgumentException when {@code colors} is not one of
         *         {@code original|16|32|64}
         */
        public MapTileColorMode getColorsMode() {
            return MapTileColorMode.fromConfigValue(colors);
        }
    }
}
