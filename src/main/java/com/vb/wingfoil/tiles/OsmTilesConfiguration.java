package com.vb.wingfoil.tiles;

import io.micronaut.context.annotation.ConfigurationProperties;
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
}
