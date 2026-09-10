package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpStatus;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Unit tests for {@link TileCompositeService} against a local stub tile source covering: exact-size
 * compositing, composite caching (HIT/miss/skipCache), partial-failure gray fill, all-failure 502,
 * the Web-Mercator centre-tile math, and headless {@link BufferedImage} creation.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileCompositeServiceTest implements TestPropertyProvider {

    private static final double LAT = 60.068347;

    private static final double LON = 30.002349;

    private static final int ZOOM = 15;

    /** Solid colour the stub paints every successfully served tile with. */
    private static final Color TILE_COLOR = new Color(30, 144, 255);

    private static final byte[] TILE_PNG = solidPng(TILE_COLOR);

    static HttpServer stubServer;

    static int port;

    static Path cacheDir;

    static final Set<String> FAILING_PATHS = ConcurrentHashMap.newKeySet();

    static final AtomicBoolean FAIL_ALL = new AtomicBoolean(false);

    static final AtomicInteger TOTAL_REQUESTS = new AtomicInteger(0);

    private TileCompositeService compositeService;

    private SyncCache<Cache> tileCache;

    private SyncCache<Cache> compositeCache;

    @Override
    public Map<String, String> getProperties() {
        try {
            stubServer = startStubServer();
            port = stubServer.getAddress().getPort();
            cacheDir = Files.createTempDirectory("windsensorbackend-test-ehcache");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub tile server", e);
        }
        return Map.of("osm-tiles.base-url", "http://127.0.0.1:" + port, "ehcache.storage-path", cacheDir.toString());
    }

    @AfterAll
    void stopStubServer() throws IOException {
        if (stubServer != null) {
            stubServer.stop(0);
        }
        if (cacheDir != null) {
            try (var files = Files.walk(cacheDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    // Constructor injection of app beans does not work together with TestPropertyProvider in
    // micronaut-test-junit5 5.0.0, so the beans are looked up from the context per test. Every test
    // starts from cleared stub state and empty caches so assertions do not depend on prior runs.
    @BeforeEach
    void initBeans(ApplicationContext ctx) {
        this.compositeService = ctx.getBean(TileCompositeService.class);
        this.tileCache = (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(OsmTileService.CACHE_NAME))
                .orElseThrow();
        this.compositeCache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(TileCompositeService.CACHE_NAME))
                        .orElseThrow();
        FAILING_PATHS.clear();
        FAIL_ALL.set(false);
        TOTAL_REQUESTS.set(0);
        tileCache.invalidateAll();
        compositeCache.invalidateAll();
    }

    @Test
    void composeProducesExactDimensionsOnCacheMiss() throws IOException {
        var result = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);

        assertEquals(OsmTileService.CacheStatus.MISS, result.status());
        assertFalse(result.partial());
        var img = decode(result.png());
        assertNotNull(img);
        assertEquals(454, img.getWidth(), "composite must be exactly the requested width");
        assertEquals(454, img.getHeight(), "composite must be exactly the requested height");
    }

    @Test
    void repeatComposeIsServedFromCompositeCache() {
        var first = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertEquals(OsmTileService.CacheStatus.MISS, first.status());
        var requestsAfterFirst = TOTAL_REQUESTS.get();

        var second = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertEquals(OsmTileService.CacheStatus.HIT, second.status());
        assertArrayEquals(first.png(), second.png(), "cache hit must serve the stored bytes");
        assertEquals(requestsAfterFirst, TOTAL_REQUESTS.get(), "a composite cache hit must not touch upstream");
    }

    @Test
    void skipCacheBypassesCompositeCache() {
        var warm = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertEquals(OsmTileService.CacheStatus.MISS, warm.status());

        var forced = compositeService.compose(ZOOM, LAT, LON, 454, 454, true);
        assertNotEquals(
                OsmTileService.CacheStatus.HIT,
                forced.status(),
                "skipCache=true must not serve the composite from cache");
    }

    @Test
    void partialFailureFillsFailedRegionGrayAndFlagsPartial() throws IOException {
        var centre = TileCompositeService.centreTileIndex(ZOOM, LAT, LON);
        FAILING_PATHS.add(ZOOM + "/" + centre[0] + "/" + centre[1]);

        var result = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertTrue(result.partial(), "a failed centre tile must mark the composite partial");

        var img = decode(result.png());
        assertEquals(454, img.getWidth());
        assertEquals(
                (192 << 16) | (192 << 8) | 192,
                img.getRGB(227, 227) & 0xFFFFFF,
                "failed centre region must be neutral gray");
        assertEquals(
                rgb(TILE_COLOR), img.getRGB(8, 8) & 0xFFFFFF, "a neighbouring successful tile must show its content");
    }

    @Test
    void allComponentFailuresThrowBadGateway() {
        FAIL_ALL.set(true);

        var ex =
                assertThrows(TileProxyException.class, () -> compositeService.compose(ZOOM, LAT, LON, 454, 454, false));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
    }

    @Test
    void bufferedImageCreationIsHeadlessSafe() throws IOException {
        var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 64, 64);
        g.dispose();

        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        assertTrue(out.size() > 0, "headless BufferedImage must encode to a non-empty PNG");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void centreTileMatchesWatchAppCalculation() {
        var centre = TileCompositeService.centreTileIndex(15, 60.068347, 30.002349);
        assertEquals(19114, centre[0], "centre tile x must match the watch app's Web-Mercator math");
        assertEquals(9503, centre[1], "centre tile y must match the watch app's Web-Mercator math");
    }

    private static int rgb(Color c) {
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static byte[] solidPng(Color color) {
        var img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 256, 256);
        g.dispose();
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build stub tile PNG", e);
        }
    }

    private static HttpServer startStubServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", TileCompositeServiceTest::serve);
        server.start();
        return server;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath().substring(1);
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        TOTAL_REQUESTS.incrementAndGet();
        if (FAIL_ALL.get() || FAILING_PATHS.contains(path)) {
            respond(exchange, 404, new byte[0], Map.of());
        } else {
            respond(
                    exchange,
                    200,
                    TILE_PNG,
                    Map.of("Content-Type", "image/png", "Cache-Control", "public, max-age=604800"));
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
