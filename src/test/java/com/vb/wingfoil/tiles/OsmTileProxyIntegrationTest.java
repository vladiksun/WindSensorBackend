package com.vb.wingfoil.tiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end tile proxy test against the real upstream source ({@code tile.openstreetmap.org}).
 * Requires internet access. The watch app's fixed GPS coordinates and Web-Mercator centre-tile
 * math from {@code MapTileLoader.computeTiles} are replicated below so drift in the formula is
 * caught.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OsmTileProxyIntegrationTest implements TestPropertyProvider {

    /** FIXED_GPS_COORDINATES = [60.068347, 30.002349] as [latitude, longitude] in Commons.mc. */
    private static final double FIXED_LATITUDE = 60.068347;

    private static final double FIXED_LONGITUDE = 30.002349;

    private static final int ZOOM = 15;

    @Override
    public Map<String, String> getProperties() {
        // ehcache.storage-path comes from application-test.yml (kept out of /var/lib in tests).
        return Map.of("micronaut.server.ssl.enabled", "false");
    }

    @Test
    void centreTileMatchesWatchAppCalculation() {
        var tile = computeCentreTile(FIXED_LATITUDE, FIXED_LONGITUDE, ZOOM);

        assertEquals(ZOOM, tile.z());
        assertEquals(19114, tile.x());
        assertEquals(9503, tile.y());
    }

    @Test
    void realTileIsFetchedThenServedFromCache(RequestSpecification spec) {
        var tile = computeCentreTile(FIXED_LATITUDE, FIXED_LONGITUDE, ZOOM);
        var path = "/tiles/" + tile.z() + "/" + tile.x() + "/" + tile.y() + ".png";

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
