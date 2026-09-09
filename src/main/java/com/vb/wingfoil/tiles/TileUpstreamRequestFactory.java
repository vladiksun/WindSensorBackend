package com.vb.wingfoil.tiles;

import org.apache.hc.client5.http.classic.methods.HttpGet;

/**
 * Builds outbound GET requests to the upstream tile source. The identifying User-Agent and Referer
 * are applied explicitly on every request so that no library-default User-Agent can ever be
 * emitted, and no {@code no-cache} directive is ever sent upstream.
 */
public final class TileUpstreamRequestFactory {

    public static final String USER_AGENT_HEADER = "User-Agent";

    public static final String REFERER_HEADER = "Referer";

    private final OsmTilesConfiguration config;

    public TileUpstreamRequestFactory(OsmTilesConfiguration config) {
        this.config = config;
    }

    /**
     * @param url  full upstream tile URL ({@code {base}/{z}/{x}/{y}.png})
     * @param etag stored upstream Etag, or null for an unconditional fetch
     * @return a GET request carrying the configured User-Agent/Referer and, when an Etag is given,
     *     If-None-Match
     */
    public HttpGet createRequest(String url, String etag) {
        var request = new HttpGet(url);
        request.setHeader(USER_AGENT_HEADER, config.getUserAgent());
        request.setHeader(REFERER_HEADER, config.getReferer());
        if (etag != null && !etag.isBlank()) {
            request.setHeader("If-None-Match", etag);
        }
        return request;
    }
}
