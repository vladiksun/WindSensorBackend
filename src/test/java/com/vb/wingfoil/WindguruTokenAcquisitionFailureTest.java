package com.vb.wingfoil;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.cache.SensorDataResponseCache;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.util.Map;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@code POST /sensor-data} for a Windguru sensor whose upstream refuses to hand out an access token. The refusal is
 * the provider's own failure and it happens before any station data was asked for, so the client is answered 502 Bad
 * Gateway with the authentication sentence — not the generic upstream text, and not a 401 that would suggest the
 * client's own request was rejected — while the response cache stores nothing a later request could answer from.
 *
 * <p>The whole exchange stays on loopback: {@link StubUpstreamServer} serves both faces of this provider family, with
 * its token action refusing and its station-data path kept out of reach on purpose.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindguruTokenAcquisitionFailureTest implements TestPropertyProvider {

    private static final String STATION_ID = "57410";

    /** Paths of the refused family: the token action, and the station-data call it was supposed to unlock. */
    private static final String TOKEN_PATH = "/api/windguru-denied/";

    private static final String STATION_PATH = "/api/windguru-denied/" + STATION_ID;

    private static StubUpstreamServer upstream;

    private SensorDataResponseCache responseCache;

    @Override
    public @NonNull Map<String, String> getProperties() {
        // Created once for the class: the embedded application keeps whichever URL it read first, so a second server
        // per test method would leave that application pointing at a closed one.
        if (upstream == null) {
            try {
                upstream = new StubUpstreamServer();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the stub upstream", e);
            }
        }

        return Map.of(
                "spec.name",
                "WindguruTokenAcquisitionFailureTest",
                "wind-sensor.wind-providers.windguru.url",
                upstream.deniedWindguruUrlTemplate(),
                // The cache is switched on here so that "nothing was stored" is a fact the test can look at rather
                // than a claim about a cache that was never consulted.
                "wind-sensor.sensor-data-cache.enabled",
                "true",
                "wind-sensor.sensor-data-cache.ttl",
                "30s",
                "wind-sensor.sensor-data-cache.floating-window",
                "0s");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.close();
            upstream = null;
        }
    }

    @BeforeEach
    void resetCacheAndRecords(ApplicationContext ctx) {
        this.responseCache = ctx.getBean(SensorDataResponseCache.class);

        // The cache has to be emptied through the Ehcache instance itself: SensorDataResponseCache only looks up and
        // stores entries, and a leftover entry from another test would answer this one.
        var ehcache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(SensorDataResponseCache.CACHE_NAME))
                        .orElseThrow();

        ehcache.invalidateAll();
        upstream.resetRecords();
    }

    @Test
    void aRefusedTokenMintAnswersBadGatewayWithoutEverAskingForStationData(RequestSpecification spec) {
        var key = SensorDataResponseCache.cacheKey("windguru", STATION_ID, 3600, 5);

        var refused = postSensorData(spec, STATION_ID, "windguru", 3600, 5);
        refused.then().statusCode(502).body("message", is("Windguru authentication unavailable"));

        assertAuthenticationFailureMessage(refused);
        assertTrue(responseCache.lookup(key).isEmpty(), "a refusal stores nothing a later request could answer from");
        assertEquals(1, upstream.callsFor(TOKEN_PATH), "the token action was asked once");
        assertEquals(0, upstream.callsFor(STATION_PATH), "no unauthenticated station-data call was ever spent");

        var refusedAgain = postSensorData(spec, STATION_ID, "windguru", 3600, 5);
        refusedAgain.then().statusCode(502);

        assertAuthenticationFailureMessage(refusedAgain);
        assertTrue(responseCache.lookup(key).isEmpty(), "the second refusal stored nothing either");
        assertEquals(2, upstream.callsFor(TOKEN_PATH), "the next identical request has to mint again");
        assertEquals(0, upstream.callsFor(STATION_PATH), "and it still never reaches station data");
    }

    /**
     * The client-facing text of a refused token: one short sentence naming the provider's authentication, and none of
     * the internal request details — no loopback host, no path, no query string. Those belong in the server logs as
     * the cause of the failure, never in the body the client reads.
     */
    private static void assertAuthenticationFailureMessage(Response response) {
        var message = response.jsonPath().getString("message");

        assertNotNull(message, "the failure has to name a cause");
        assertTrue(message.length() <= 60, "a short description, not internal detail: " + message);
        assertFalse(message.contains("127.0.0.1"), "no provider host in the client body: " + message);
        assertFalse(message.contains("/api/windguru-denied/"), "no provider path in the client body: " + message);
        assertFalse(message.contains("?"), "no query string in the client body: " + message);
    }

    private static Response postSensorData(
            RequestSpecification spec, String sensorId, String provider, int readingWindow, int numberOfReadings) {
        String body = """
                {
                  "readingWindow": %d,
                  "numberOfReadings": %d,
                  "sensor": {
                    "id": "%s",
                    "provider": "%s"
                  }
                }
                """.formatted(readingWindow, numberOfReadings, sensorId, provider);

        return spec.contentType(ContentType.JSON).body(body).when().post("/sensor-data");
    }
}
