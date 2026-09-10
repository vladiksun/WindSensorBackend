package com.vb.wingfoil.tiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end tile proxy test against the real upstream source ({@code tile.openstreetmap.org}).
 * Requires internet access. The watch app's fixed GPS coordinates and Web-Mercator centre-tile
 * math from {@code MapTileLoader.computeTiles} are replicated below so drift in the formula is
 * caught. A fresh EhCache storage directory per run keeps the cache empty, so MISS/HIT assertions
 * do not depend on entries left behind by earlier runs.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OsmTileProxyIntegrationTest implements TestPropertyProvider {

    /** FIXED_GPS_COORDINATES = [60.068347, 30.002349] as [latitude, longitude] in Commons.mc. */
    private static final double FIXED_LATITUDE = 60.068347;

    private static final double FIXED_LONGITUDE = 30.002349;

    private static final int ZOOM = 15;

    static Path cacheDir;

    @Override
    public Map<String, String> getProperties() {
        try {
            cacheDir = Files.createTempDirectory("windsensorbackend-test-ehcache");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temp EhCache storage dir", e);
        }
        return Map.of("micronaut.server.ssl.enabled", "false", "ehcache.storage-path", cacheDir.toString());
    }

    @AfterAll
    void removeCacheDir() throws IOException {
        if (cacheDir != null) {
            try (var files = Files.walk(cacheDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void centreTileMatchesWatchAppCalculation() {
        var tile = computeCentreTile(FIXED_LATITUDE, FIXED_LONGITUDE, ZOOM);

        assertEquals(ZOOM, tile.z());
        assertEquals(19114, tile.x());
        assertEquals(9503, tile.y());
    }

    @Order(1)
    @Test
    void realTileIsFetchedThenServedFromCache(RequestSpecification spec) {
        var tile = computeCentreTile(FIXED_LATITUDE, FIXED_LONGITUDE, ZOOM);
        var path = "/tiles/" + tile.z() + "/" + tile.x() + "/" + tile.y() + ".png";

        // Runs first on a cold cache (fresh storage dir per run), so the first request must be a MISS.
        var first = given(spec).accept("image/png").when().get(path);
        first.then().statusCode(is(200)).contentType("image/png").header("X-Cache", "MISS");
        var firstBytes = first.getBody().asByteArray();
        assertEquals((byte) 0x89, firstBytes[0], "PNG magic byte 1");
        assertEquals((byte) 0x50, firstBytes[1], "PNG magic byte 2 ('P')");
        assertEquals((byte) 0x4E, firstBytes[2], "PNG magic byte 3 ('N')");
        assertEquals((byte) 0x47, firstBytes[3], "PNG magic byte 4 ('G')");

        var second = given(spec).accept("image/png").when().get(path);
        second.then().statusCode(is(200)).contentType("image/png").header("X-Cache", "HIT");
        assertArrayEquals(firstBytes, second.getBody().asByteArray(), "second lookup must serve the cached bytes");
    }

    @Order(2)
    @Test
    void skipCacheParameterForcesUpstreamFetch(RequestSpecification spec) {
        var tile = computeCentreTile(FIXED_LATITUDE, FIXED_LONGITUDE, ZOOM);
        var path = "/tiles/" + tile.z() + "/" + tile.x() + "/" + tile.y() + ".png";

        // Warm the cache so a plain request would be served from it.
        given(spec).accept("image/png").when().get(path).then().statusCode(is(200));
        given(spec)
                .accept("image/png")
                .when()
                .get(path)
                .then()
                .statusCode(is(200))
                .header("X-Cache", "HIT");

        // skipCache=true must bypass the valid entry and go to upstream (never HIT).
        var forced = given(spec).accept("image/png").when().get(path + "?skipCache=true");
        forced.then().statusCode(is(200)).contentType("image/png");
        var xCache = forced.getHeader("X-Cache");
        assertTrue(Set.of("MISS", "REVALIDATED").contains(xCache), "skipCache=true must not serve from cache");

        // The forced fetch repopulated the cache, so a plain request hits again.
        given(spec)
                .accept("image/png")
                .when()
                .get(path)
                .then()
                .statusCode(is(200))
                .header("X-Cache", "HIT");
    }

    record Tile(int z, int x, int y) {}

    /**
     * Exact replica of the centre-tile part of {@code MapTileLoader.computeTiles}:
     * {@code xPix = (lon + 180) / 360 * n}, {@code yPix = (1 - ln(tan(lat) + 1/cos(lat)) / pi) / 2 * n},
     * centre tile = floor(pix / MAP_TILE_SIZE).
     */
    static Tile computeCentreTile(double latitude, double longitude, int zoom) {
        var n = 1L << zoom;
        var x = (int) Math.floor((longitude + 180.0) / 360.0 * n);
        var latRad = Math.toRadians(latitude);
        var cosLat = Math.cos(latRad);
        var tanLat = Math.sin(latRad) / cosLat;
        var y = (int) Math.floor((1.0 - Math.log(tanLat + 1.0 / cosLat) / Math.PI) / 2.0 * n);
        return new Tile(zoom, x, y);
    }
}
