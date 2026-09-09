package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Unit tests for {@link OsmTileService} against a local stub tile source covering: 200-store,
 * 304-revalidate, 404-no-store, missing-cache-headers fallback and Expires-header handling.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OsmTileServiceTest implements TestPropertyProvider {

    /** Tile served with Cache-Control max-age + Etag. */
    private static final String TILE_MAX_AGE = "15/16384/20480";

    /** Tile served with an Expires header only (far enough ahead to beat the min-ttl floor). */
    private static final String TILE_EXPIRES = "15/16385/20480";

    /** Tile served without any cache headers (fallback to min-ttl). */
    private static final String TILE_NO_HEADERS = "15/16386/20480";

    /** Tile that always 404s upstream. */
    private static final String TILE_NOT_FOUND = "15/16387/20480";

    /** Tile served with a short max-age plus Etag, used for the conditional-refetch flow. */
    private static final String TILE_SHORT_LIVED = "15/16388/20480";

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    static HttpServer stubServer;

    static int port;

    static final Map<String, AtomicInteger> REQUEST_COUNTS = new ConcurrentHashMap<>();

    private OsmTileService tileService;

    private SyncCache<Cache> tileCache;

    // Constructor injection of app beans does not work together with TestPropertyProvider in
    // micronaut-test-junit5 5.0.0 (the extension cannot resolve the parameters), so the beans are
    // looked up from the context per test instead.
    @BeforeEach
    void initBeans(ApplicationContext ctx) {
        this.tileService = ctx.getBean(OsmTileService.class);
        this.tileCache = (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(OsmTileService.CACHE_NAME))
                .orElseThrow();
    }

    @Override
    public Map<String, String> getProperties() {
        try {
            stubServer = startStubServer();
            port = stubServer.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub tile server", e);
        }
        // ehcache.storage-path comes from application-test.yml (kept out of /var/lib in tests).
        return Map.of("osm-tiles.base-url", "http://127.0.0.1:" + port);
    }

    @AfterAll
    void stopStubServer() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @Test
    void storesTileOn200AndServesHitWithinValidity() {
        var first = tileService.getTile(15, 16384, 20480).get();
        assertEquals(OsmTileService.CacheStatus.MISS, first.status());
        assertArrayEquals(PNG_BYTES, first.png());
        assertEquals(1, countOf(TILE_MAX_AGE), "first lookup must fetch upstream");

        // validity derived from Cache-Control max-age=604800 (7 days)
        assertTrue(first.expiresAtEpochMillis()
                >= System.currentTimeMillis() + Duration.ofDays(6).toMillis());
        assertTrue(first.expiresAtEpochMillis()
                <= System.currentTimeMillis() + Duration.ofDays(8).toMillis());

        var second = tileService.getTile(15, 16384, 20480).get();
        assertEquals(OsmTileService.CacheStatus.HIT, second.status());
        assertArrayEquals(first.png(), second.png());
        assertEquals(1, countOf(TILE_MAX_AGE), "second lookup within validity must not touch upstream");
    }

    private static int countOf(String tilePath) {
        return REQUEST_COUNTS.getOrDefault(tilePath, new AtomicInteger()).get();
    }

    @Test
    void revalidatesExpiredEntryWithEtag() {
        // Seed an already-expired entry carrying the upstream etag.
        var past = System.currentTimeMillis() - 1000L;
        tileCache.put(TILE_SHORT_LIVED, new CachedTile(PNG_BYTES, past, past, "\"v1\""));

        var result = tileService.getTile(15, 16388, 20480).get();

        assertEquals(OsmTileService.CacheStatus.REVALIDATED, result.status());
        assertArrayEquals(PNG_BYTES, result.png(), "304 keeps the existing bytes");
        assertTrue(result.expiresAtEpochMillis() > System.currentTimeMillis(), "304 refreshes validity");
        var stored = tileCache.get(TILE_SHORT_LIVED, CachedTile.class).orElseThrow();
        assertEquals("\"v1\"", stored.etag());
    }

    @Test
    void doesNotStoreOn404() {
        var result = tileService.getTile(15, 16387, 20480);
        assertTrue(result.isFailure(), "upstream 404 must produce a Failure");
        assertTrue(
                result.getCause() instanceof OsmTileService.TileFetchException,
                "failure cause must be TileFetchException");
        assertTrue(tileCache.get(TILE_NOT_FOUND, CachedTile.class).isEmpty(), "failed fetches must not be cached");
    }

    @Test
    void fallsBackToMinTtlWhenCacheHeadersMissing() {
        var result = tileService.getTile(15, 16386, 20480).get();

        assertEquals(OsmTileService.CacheStatus.MISS, result.status());
        // expiresAt is computed at fetch time (slightly before this assertion), so allow a small slack.
        assertTrue(
                result.expiresAtEpochMillis()
                        >= System.currentTimeMillis() + Duration.ofDays(7).toMillis() - 5_000,
                "entries without usable cache headers stay valid for at least 7 days");
    }

    @Test
    void honoursExpiresHeader() {
        var result = tileService.getTile(15, 16385, 20480).get();

        assertEquals(OsmTileService.CacheStatus.MISS, result.status());
        // The stub sends Expires = now + 30 days, beyond the 7-day min-ttl floor, so a value near
        // 30 days proves the header was parsed and honoured (a parse failure would yield ~7 days).
        var expected =
                ZonedDateTime.now(ZoneOffset.UTC).plusDays(30).toInstant().toEpochMilli();
        assertTrue(
                Math.abs(result.expiresAtEpochMillis() - expected)
                        < Duration.ofMinutes(5).toMillis(),
                "expiresAt should follow the upstream Expires header");
    }

    private static HttpServer startStubServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", OsmTileServiceTest::serve);
        server.start();
        return server;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath().substring(1);
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        REQUEST_COUNTS.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        switch (path) {
            case TILE_MAX_AGE ->
                respond(exchange, 200, PNG_BYTES, Map.of("Cache-Control", "public, max-age=604800", "ETag", "\"v1\""));
            case TILE_EXPIRES ->
                respond(
                        exchange,
                        200,
                        PNG_BYTES,
                        Map.of(
                                "Expires",
                                ZonedDateTime.now(ZoneOffset.UTC)
                                        .plusDays(30)
                                        .format(DateTimeFormatter.RFC_1123_DATE_TIME)));
            case TILE_NO_HEADERS -> respond(exchange, 200, PNG_BYTES, Map.of());
            case TILE_NOT_FOUND -> respond(exchange, 404, new byte[0], Map.of());
            case TILE_SHORT_LIVED -> {
                if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    respond(exchange, 304, new byte[0], Map.of("ETag", "\"v1\"", "Cache-Control", "max-age=604800"));
                } else {
                    respond(exchange, 200, PNG_BYTES, Map.of("Cache-Control", "max-age=1", "ETag", "\"v1\""));
                }
            }
            default -> respond(exchange, 404, new byte[0], Map.of());
        }
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, Map<String, String> headers)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
