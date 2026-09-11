package com.vb.wingfoil.tiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import javax.imageio.ImageIO;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end tests for the {@code GET /tiles/composite} endpoint against a local stub tile source
 * (deterministic, no live internet). Covers correct-dimension output, composite caching headers,
 * skipCache, parameter validation (400), partial failure (X-Partial) and all-failure (502). A fresh
 * EhCache storage directory per run plus per-test cache invalidation keeps assertions independent.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileCompositeEndpointTest implements TestPropertyProvider {

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

    static final Set<String> FAILING_PATHS = ConcurrentHashMap.newKeySet();

    static final AtomicBoolean FAIL_ALL = new AtomicBoolean(false);

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

    // Beans are looked up from the context per test (constructor injection is unavailable together
    // with TestPropertyProvider); stub state and both caches are reset so each test is independent.
    @BeforeEach
    void initBeans(ApplicationContext ctx) {
        this.tileCache = (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(OsmTileService.CACHE_NAME))
                .orElseThrow();
        this.compositeCache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(TileCompositeService.CACHE_NAME))
                        .orElseThrow();
        FAILING_PATHS.clear();
        FAIL_ALL.set(false);
        tileCache.invalidateAll();
        compositeCache.invalidateAll();
    }

    @Test
    void validRequestReturnsCorrectDimensionPng(RequestSpecification spec) throws IOException {
        var response = given(spec).accept("image/png").when().get("/tiles/composite" + VIEWPORT);

        response.then()
                .statusCode(is(200))
                .contentType("image/png")
                .header("X-Cache", org.hamcrest.Matchers.oneOf("MISS", "REVALIDATED"));
        var img = ImageIO.read(new ByteArrayInputStream(response.getBody().asByteArray()));
        assertNotNull(img);
        assertEquals(454, img.getWidth(), "composite must be exactly the requested width");
        assertEquals(454, img.getHeight(), "composite must be exactly the requested height");
    }

    @Test
    void repeatRequestHitsCompositeCache(RequestSpecification spec) {
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

    @Test
    void skipCacheForcesRebuild(RequestSpecification spec) {
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite" + VIEWPORT)
                .then()
                .statusCode(is(200));

        var forced = given(spec).accept("image/png").when().get("/tiles/composite" + VIEWPORT + "&skipCache=true");
        forced.then().statusCode(is(200));
        assertTrue(
                Set.of("MISS", "REVALIDATED").contains(forced.getHeader("X-Cache")),
                "skipCache=true must not serve the composite from cache");
    }

    @Test
    void invalidZoomRejected(RequestSpecification spec) {
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite?z=21&lat=" + LAT + "&lon=" + LON + "&width=454&height=454")
                .then()
                .statusCode(is(400));
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite?z=-1&lat=" + LAT + "&lon=" + LON + "&width=454&height=454")
                .then()
                .statusCode(is(400));
    }

    @Test
    void oversizedViewportRejected(RequestSpecification spec) {
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite?z=" + ZOOM + "&lat=" + LAT + "&lon=" + LON + "&width=2048&height=454")
                .then()
                .statusCode(is(400));
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite?z=" + ZOOM + "&lat=" + LAT + "&lon=" + LON + "&width=454&height=0")
                .then()
                .statusCode(is(400));
    }

    @Test
    void missingParameterRejected(RequestSpecification spec) {
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite?z=" + ZOOM + "&lat=" + LAT + "&lon=" + LON + "&height=454")
                .then()
                .statusCode(is(400));
    }

    @Test
    void partialFailureReturns200WithXPartial(RequestSpecification spec) throws IOException {
        var centre = TileCompositeService.centreTileIndex(ZOOM, LAT, LON);
        FAILING_PATHS.add(ZOOM + "/" + centre[0] + "/" + centre[1]);

        var response = given(spec).accept("image/png").when().get("/tiles/composite" + VIEWPORT);
        response.then().statusCode(is(200)).contentType("image/png").header("X-Partial", "true");

        var img = ImageIO.read(new ByteArrayInputStream(response.getBody().asByteArray()));
        assertEquals(454, img.getWidth());
        assertEquals(
                (192 << 16) | (192 << 8) | 192,
                img.getRGB(227, 227) & 0xFFFFFF,
                "failed centre region must be neutral gray");
    }

    @Test
    void allFailuresReturn502(RequestSpecification spec) {
        FAIL_ALL.set(true);
        given(spec)
                .accept("image/png")
                .when()
                .get("/tiles/composite" + VIEWPORT)
                .then()
                .statusCode(is(502));
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
        server.createContext("/", TileCompositeEndpointTest::serve);
        server.start();
        return server;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath().substring(1);
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
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
