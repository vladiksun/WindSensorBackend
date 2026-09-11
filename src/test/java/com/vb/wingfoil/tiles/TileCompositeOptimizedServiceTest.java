package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@link TileCompositeService} behaviour when {@code osm-tiles.optimization.enabled=true}: the
 * composite is an indexed PNG with a bounded palette, repeats hit the composite cache within a mode,
 * and the tile cache still holds the original upstream bytes (quantization never feeds back into it).
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileCompositeOptimizedServiceTest implements TestPropertyProvider {

    private static final double LAT = 60.068347;

    private static final double LON = 30.002349;

    private static final int ZOOM = 15;

    /** Solid colour the stub paints every successfully served tile with. */
    private static final Color TILE_COLOR = new Color(30, 144, 255);

    private static final byte[] TILE_PNG = solidPng(TILE_COLOR);

    static HttpServer stubServer;

    static int port;

    static Path cacheDir;

    private TileCompositeService compositeService;

    private OsmTileService tileService;

    private SyncCache<Cache> compositeCache;

    // Constructor injection of app beans does not work together with TestPropertyProvider in
    // micronaut-test-junit5 5.0.0, so the beans are looked up from the context per test (same
    // pattern as TileCompositeServiceTest). The PER_CLASS lifecycle shares one context and one
    // EhCache across methods, so composites are cleared before each test to start cold.
    @BeforeEach
    void initBeans(ApplicationContext ctx) {
        this.compositeService = ctx.getBean(TileCompositeService.class);
        this.tileService = ctx.getBean(OsmTileService.class);
        this.compositeCache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(TileCompositeService.CACHE_NAME))
                        .orElseThrow();
        compositeCache.invalidateAll();
    }

    @Override
    public Map<String, String> getProperties() {
        try {
            stubServer = startStubServer();
            port = stubServer.getAddress().getPort();
            cacheDir = Files.createTempDirectory("windsensorbackend-test-ehcache");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub tile server", e);
        }
        return Map.of(
                "osm-tiles.base-url",
                "http://127.0.0.1:" + port,
                "ehcache.storage-path",
                cacheDir.toString(),
                "osm-tiles.optimization.enabled",
                "true",
                "osm-tiles.optimization.colors",
                "32");
    }

    @AfterAll
    void stopStubServer() throws IOException {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @Test
    void optimizedComposeProducesIndexedPngWithBoundedPalette() throws IOException {
        var result = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertEquals(OsmTileService.CacheStatus.MISS, result.status());
        var decoded = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertTrue(decoded.getColorModel() instanceof IndexColorModel, "optimized composite must be indexed");
        // The JDK PNG reader normalises the decoded colour model to a 256-entry palette, so the
        // bounded palette is verified against the actual PLTE chunk written to the file.
        assertTrue(plteEntries(result.png()) <= 32, "PLTE must hold at most 32 entries");
        assertFalse(((IndexColorModel) decoded.getColorModel()).hasAlpha(), "composite must remain fully opaque");
        assertEquals(454, decoded.getWidth());
        assertEquals(454, decoded.getHeight());
    }

    @Test
    void repeatOptimizedComposeHitsCacheWithinMode() {
        var first = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        var second = compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        assertEquals(OsmTileService.CacheStatus.MISS, first.status());
        assertEquals(OsmTileService.CacheStatus.HIT, second.status());
        assertArrayEquals(first.png(), second.png());
    }

    @Test
    void tileCacheStillHoldsOriginalUpstreamBytesAfterOptimizedCompose() {
        compositeService.compose(ZOOM, LAT, LON, 454, 454, false);
        var centre = TileCompositeService.centreTileIndex(ZOOM, LAT, LON);
        var tile = tileService.getTile(ZOOM, (int) centre[0], (int) centre[1]).get();
        assertArrayEquals(
                TILE_PNG, tile.png(), "the tile cache must hold the original upstream bytes, not quantized ones");
    }

    /** Number of entries in the PNG {@code PLTE} chunk, or -1 when absent. */
    private static int plteEntries(byte[] png) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = ((png[pos] & 0xFF) << 24)
                    | ((png[pos + 1] & 0xFF) << 16)
                    | ((png[pos + 2] & 0xFF) << 8)
                    | (png[pos + 3] & 0xFF);
            var type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("PLTE")) {
                return len / 3;
            }
            if (type.equals("IEND")) {
                break;
            }
            pos += 12 + len;
        }
        return -1;
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
        server.createContext("/", TileCompositeOptimizedServiceTest::serve);
        server.start();
        return server;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        respond(
                exchange,
                200,
                TILE_PNG,
                Map.of("Content-Type", "image/png", "Cache-Control", "public, max-age=604800"));
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
