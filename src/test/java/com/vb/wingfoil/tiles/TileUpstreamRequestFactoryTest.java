package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.hc.core5.http.HttpMessage;
import org.junit.jupiter.api.Test;

class TileUpstreamRequestFactoryTest {

    private static final String USER_AGENT =
            "WindSensor/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)";

    private static final String REFERER = "https://github.com/vladiksun/WindSensor";

    private static final String TILE_URL = "http://tiles.example/15/19114/9503.png";

    private TileUpstreamRequestFactory factory() {
        var config = new OsmTilesConfiguration();
        config.setBaseUrl("http://tiles.example");
        config.setUserAgent(USER_AGENT);
        config.setReferer(REFERER);
        return new TileUpstreamRequestFactory(config);
    }

    @Test
    void appliesConfiguredIdentifyingHeadersExactly() {
        var request = factory().createRequest(TILE_URL, null);

        assertEquals(USER_AGENT, headerValue(request, TileUpstreamRequestFactory.USER_AGENT_HEADER));
        assertEquals(REFERER, headerValue(request, TileUpstreamRequestFactory.REFERER_HEADER));
        assertNull(request.getFirstHeader("If-None-Match"));
    }

    @Test
    void addsConditionalHeaderOnlyWhenEtagPresent() {
        var request = factory().createRequest(TILE_URL, "\"etag-1\"");

        assertEquals("\"etag-1\"", headerValue(request, "If-None-Match"));
    }

    @Test
    void omitsConditionalHeaderForBlankEtag() {
        var request = factory().createRequest(TILE_URL, "  ");

        assertNull(request.getFirstHeader("If-None-Match"));
    }

    @Test
    void neverSendsNoCacheDirectiveUpstream() {
        var request = factory().createRequest(TILE_URL, null);

        assertFalse(request.containsHeader("Cache-Control"));
    }

    @Test
    void userAgentIsNotALibraryDefault() {
        var request = factory().createRequest(TILE_URL, null);
        var userAgent = headerValue(request, TileUpstreamRequestFactory.USER_AGENT_HEADER);

        assertFalse(userAgent.startsWith("Apache-HttpClient"), "must not leak the HttpClient default UA");
        assertFalse(userAgent.startsWith("okhttp"), "must not leak an okhttp-style UA");
        assertEquals(USER_AGENT, userAgent);
    }

    private static String headerValue(HttpMessage message, String name) {
        var header = message.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }
}
