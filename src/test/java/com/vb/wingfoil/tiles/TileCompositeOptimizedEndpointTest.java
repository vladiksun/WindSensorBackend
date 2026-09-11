package com.vb.wingfoil.tiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.specification.RequestSpecification;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
 * End-to-end tests for {@code GET /tiles/composite} with optimization enabled: the response is an
 * indexed PNG with a bounded palette, carries the X-Cache header, and repeats are served from the
 * composite cache within the configured mode.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileCompositeOptimizedEndpointTest implements TestPropertyProvider {

    private static final double LAT = 60.068347;

    private static final double LON = 30.002349;

    private static final int ZOOM = 15;

    private static final String VIEWPORT = "?z=" + ZOOM + "&lat=" + LAT + "&lon=" + LON + "&width=454&height=454";

    /** Solid colour the stub paints every successfully served tile with. */
    private static final Color TILE_COLOR = new Color(30, 144, 255);

    private static final byte[] TILE_PNG = solidPng(TILE_COLOR);

    static HttpServer stubServer;

    static int port;

    static Path cacheDir;

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

    @BeforeEach
    void resetCaches(ApplicationContext ctx) {
        this.compositeCache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(TileCompositeService.CACHE_NAME))
                        .orElseThrow();
        compositeCache.invalidateAll();
    }

    @Test
    void optimizedCompositeIsIndexedPngWithCacheHeader(RequestSpecification spec) throws IOException {
        var response = given(spec).accept("image/png").when().get("/tiles/composite" + VIEWPORT);
        response.then()
                .statusCode(is(200))
                .contentType("image/png")
                .header("X-Cache", org.hamcrest.Matchers.oneOf("MISS", "REVALIDATED"));
        var img = ImageIO.read(new ByteArrayInputStream(response.getBody().asByteArray()));
        assertTrue(img.getColorModel() instanceof IndexColorModel, "optimized composite must be indexed");
        assertEquals(454, img.getWidth());
        assertEquals(454, img.getHeight());
    }

    @Test
    void repeatRequestHitsCompositeCacheWithinMode(RequestSpecification spec) {
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite" + VIEWPORT)
                .then()
                .statusCode(is(200));
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite" + VIEWPORT)
                .then()
                .statusCode(is(200))
                .header("X-Cache", "HIT");
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
        server.createContext("/", TileCompositeOptimizedEndpointTest::serve);
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
