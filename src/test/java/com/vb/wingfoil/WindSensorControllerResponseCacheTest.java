package com.vb.wingfoil;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import com.vb.wingfoil.cache.CachedSensorReadings;
import com.vb.wingfoil.cache.SensorDataResponseCache;
import com.vb.wingfoil.provider.impl.BaseWindyDataProvider;
import com.vb.wingfoil.response.windy.WindyMeasurement;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@code POST /sensor-data} with the response cache switched on, against a loopback upstream whose every call is
 * counted. The assertions pair what the client sees ({@code X-Cache}, status, body) with what actually happened
 * upstream, because a header alone cannot prove that no provider call was made.
 *
 * <p>Caching is re-enabled here only for this class, with {@code floating-window=0s} so entries expire at exactly
 * their TTL; an already-expired entry is seeded straight into the cache instead of waiting out a real TTL. Each test
 * clears the cache first, so no entry leaks into the next test.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindSensorControllerResponseCacheTest implements TestPropertyProvider {

    private static StubUpstreamServer upstream;

    private SensorDataResponseCache responseCache;

    private SyncCache<Cache> ehcache;

    @Override
    public @NonNull Map<String, String> getProperties() {
        // Created once for the class: the embedded application keeps whichever URLs it read first, so a second
        // server per test method would leave that application pointing at a closed one.
        if (upstream == null) {
            try {
                upstream = new StubUpstreamServer();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the stub upstream", e);
            }
        }

        return Map.of(
                "spec.name",
                "WindSensorControllerResponseCacheTest",
                "wind-sensor.wind-providers.windy.url",
                upstream.windyUrlTemplate(),
                "wind-sensor.wind-providers.probe.url",
                upstream.probeUrlTemplate(),
                "wind-sensor.sensor-data-cache.enabled",
                "true",
                "wind-sensor.sensor-data-cache.ttl",
                "30s",
                "wind-sensor.sensor-data-cache.floating-window",
                "0s",
                // The stalled-upstream case has to fail on a bound the test can rely on rather than on an
                // unbounded wait that would outlast the test itself.
                "wind-sensor.upstream-response-timeout-seconds",
                "1");
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
        this.ehcache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(SensorDataResponseCache.CACHE_NAME))
                        .orElseThrow();

        ehcache.invalidateAll();
        upstream.resetRecords();
    }

    @Test
    void aSecondIdenticalRequestIsAnsweredFromTheCacheWithoutTouchingTheProvider(RequestSpecification spec) {
        var fetched = postSensorData(spec, "12345", "windy", 3600, 5);
        fetched.then().statusCode(200).header("X-Cache", "MISS").body("size()", is(2));

        var answered = postSensorData(spec, "12345", "windy", 3600, 5);
        answered.then()
                .statusCode(200)
                .header("X-Cache", "HIT")
                .body("size()", is(2))
                .body("[0].windMax", is(7.5f))
                .body("[1].windAvg", is(6.0f));

        assertEquals(fetched.body().asString(), answered.body().asString(), "a cached answer carries the same body");
        assertEquals(1, upstream.callsFor("/api/windy/12345"), "the entry answered instead of a provider call");
    }

    @Test
    void anEntryWhoseValidityEndedIsReplacedByAFreshProviderCall(RequestSpecification spec) {
        var key = SensorDataResponseCache.cacheKey("windy", "12345", 3600, 5);
        var past = System.currentTimeMillis() - 1_000L;
        // The only way to test expiry without waiting out a TTL: an entry that is already past its own expiry.
        ehcache.put(key, new CachedSensorReadings(List.of(new SensorDataDTO(1.0f, 1.0f, 1.0f, 1.0f, 1L)), past, past));

        postSensorData(spec, "12345", "windy", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                // The fresh upstream values, not the stale ones the expired entry still held.
                .body("[0].windMax", is(7.5f));

        assertEquals(1, upstream.callsFor("/api/windy/12345"), "an expired entry must not answer a request");

        postSensorData(spec, "12345", "windy", 3600, 5).then().statusCode(200).header("X-Cache", "HIT");
        assertEquals(1, upstream.callsFor("/api/windy/12345"), "the refetch replaced the entry rather than joining it");
    }

    @Test
    void aFailedProviderCallIsNotCachedSoTheNextIdenticalRequestCallsTheProviderAgain(RequestSpecification spec) {
        var key = SensorDataResponseCache.cacheKey("windy", StubUpstreamServer.UNAVAILABLE_SENSOR_ID, 3600, 5);

        // The provider answered 503, so the client is answered 503 — not a generic 500 whose body recites the
        // internal fetch URL.
        var refused = postSensorData(spec, StubUpstreamServer.UNAVAILABLE_SENSOR_ID, "windy", 3600, 5);
        refused.then().statusCode(503);
        assertSanitizedFailureMessage(refused);

        assertTrue(responseCache.lookup(key).isEmpty(), "a failure stores nothing that a later request could reuse");
        assertEquals(1, upstream.callsFor("/api/windy/unavailable"));

        var refusedAgain = postSensorData(spec, StubUpstreamServer.UNAVAILABLE_SENSOR_ID, "windy", 3600, 5);
        refusedAgain.then().statusCode(503);
        assertSanitizedFailureMessage(refusedAgain);

        assertEquals(2, upstream.callsFor("/api/windy/unavailable"), "an error is never remembered as an answer");
    }

    @Test
    void aForbiddenProviderCallIsAnsweredAsForbiddenAndNeverCached(RequestSpecification spec) {
        var key = SensorDataResponseCache.cacheKey("windy", StubUpstreamServer.FORBIDDEN_SENSOR_ID, 3600, 5);

        // A provider that has blocked this host answers 403; the client is told exactly that, because "the provider
        // refused us" and "our backend broke" are different facts an operator cannot tell apart from a 500.
        var forbidden = postSensorData(spec, StubUpstreamServer.FORBIDDEN_SENSOR_ID, "windy", 3600, 5);
        forbidden.then().statusCode(403);
        assertSanitizedFailureMessage(forbidden);

        assertTrue(responseCache.lookup(key).isEmpty(), "a refusal stores nothing");
        assertEquals(1, upstream.callsFor("/api/windy/forbidden"));

        postSensorData(spec, StubUpstreamServer.FORBIDDEN_SENSOR_ID, "windy", 3600, 5)
                .then()
                .statusCode(403);

        assertEquals(2, upstream.callsFor("/api/windy/forbidden"), "the next identical request reaches upstream again");
    }

    @Test
    void anUpstreamThatAcceptsTheConnectionAndThenStallsIsNotCached(RequestSpecification spec) {
        var key = SensorDataResponseCache.cacheKey("windy", StubUpstreamServer.STALLED_SENSOR_ID, 3600, 5);

        // The stub holds the response for four seconds while this context bounds a response at one second. No upstream
        // status was ever available, so the client is told 502 Bad Gateway rather than a generic 500.
        postSensorData(spec, StubUpstreamServer.STALLED_SENSOR_ID, "windy", 3600, 5)
                .then()
                .statusCode(502);

        assertTrue(responseCache.lookup(key).isEmpty(), "a timed-out call stores nothing");
        assertEquals(1, upstream.callsFor("/api/windy/stalled"));

        // The abandoned connection of the timed-out call is only released when the stub finishes holding it, so the
        // next identical request is issued after that — otherwise it would fail on a dead socket instead of showing
        // whether a second provider call happened.
        sleepQuietly(upstream.stalledResponseDelay().plusSeconds(1));

        postSensorData(spec, StubUpstreamServer.STALLED_SENSOR_ID, "windy", 3600, 5)
                .then()
                .statusCode(502);

        assertTrue(responseCache.lookup(key).isEmpty(), "a timeout left nothing behind to answer from");
        assertTrue(
                upstream.awaitCallsFor("/api/windy/stalled", 2, Duration.ofSeconds(10)),
                "a timed-out provider call is not remembered as an answer");
    }

    @Test
    void aSuccessfulFetchThatProducedNoReadingsIsCachedLikeAnyOtherSuccess(RequestSpecification spec) {
        // An upstream payload with no readings at all still answers with the provider's zero reading, and that answer
        // is a successful fetch: it is cached exactly as a payload full of readings would be.
        postSensorData(spec, StubUpstreamServer.EMPTY_SENSOR_ID, "windy", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f))
                .body("[0].timestamp", is(0));

        postSensorData(spec, StubUpstreamServer.EMPTY_SENSOR_ID, "windy", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "HIT")
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f));

        assertEquals(1, upstream.callsFor("/api/windy/empty"), "an empty answer is still an answer the provider gave");
    }

    @Test
    void differentWindowsAndCountsForOneSensorAreSeparateEntries(RequestSpecification spec) {
        postSensorData(spec, "12345", "windy", 3600, 5).then().statusCode(200).header("X-Cache", "MISS");
        postSensorData(spec, "12345", "windy", 7200, 5).then().statusCode(200).header("X-Cache", "MISS");
        postSensorData(spec, "12345", "windy", 3600, 2).then().statusCode(200).header("X-Cache", "MISS");

        assertEquals(3, upstream.callsFor("/api/windy/12345"), "each parameter combination is its own entry");

        postSensorData(spec, "12345", "windy", 3600, 5).then().statusCode(200).header("X-Cache", "HIT");
        postSensorData(spec, "12345", "windy", 7200, 5).then().statusCode(200).header("X-Cache", "HIT");
        postSensorData(spec, "12345", "windy", 3600, 2).then().statusCode(200).header("X-Cache", "HIT");

        assertEquals(3, upstream.callsFor("/api/windy/12345"), "all three entries answered on their own");
    }

    @Test
    void theSameSensorIdUnderTwoProvidersNeverSharesAnEntry(RequestSpecification spec) {
        postSensorData(spec, "0264", "windy", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                .body("[1].windMax", is(8.0f));

        postSensorData(spec, "0264", "probe", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                // The probe provider's own readings for the same id.
                .body("[0].windDirection", is(270.0f));

        assertEquals(1, upstream.callsFor("/api/windy/0264"));
        assertEquals(1, upstream.callsFor("/api/probe/0264"), "a provider never answers with another provider's entry");

        postSensorData(spec, "0264", "windy", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "HIT")
                .body("[1].windMax", is(8.0f));
        postSensorData(spec, "0264", "probe", 3600, 5)
                .then()
                .statusCode(200)
                .header("X-Cache", "HIT")
                .body("[0].windDirection", is(270.0f));
    }

    @Test
    void everyProviderCallStillCarriesTheHeadersItsOwnProviderDeclares(RequestSpecification spec) {
        postSensorData(spec, "0264", "probe", 3600, 5).then().statusCode(200).header("X-Cache", "MISS");

        var headers = upstream.headersFor("/api/probe/0264");

        // Exactly one value per header: the provider's own Accept replaces the JSON default instead of adding to it.
        assertEquals(List.of("application/json"), headers.get("Accept"));
        assertEquals(List.of(HeaderProbeProvider.TEST_HEADER_VALUE), headers.get(HeaderProbeProvider.TEST_HEADER_NAME));
    }

    /**
     * The client-facing error text of a failed {@code /sensor-data} call: one short generic sentence, and none of the
     * internal request details — no provider host, no path, no query string, no recital of the upstream call. Those
     * belong to the server logs, where they travel as the cause of the failure rather than as its message.
     */
    private static void assertSanitizedFailureMessage(Response response) {
        var message = response.jsonPath().getString("message");

        assertNotNull(message, "the failure has to name a cause");
        assertTrue(message.length() <= 60, "a short description, not internal detail: " + message);
        assertFalse(message.contains("127.0.0.1"), "no provider host in the client body: " + message);
        assertFalse(message.contains("/api/windy/"), "no provider path in the client body: " + message);
        assertFalse(message.contains("?"), "no query string in the client body: " + message);
        assertFalse(message.contains("Upstream call failed"), "no internal fetch text in the client body: " + message);
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

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A second provider in this context only: proves a declared extra header reaches upstream through the cache. */
    @Singleton
    @Requires(property = "spec.name", value = "WindSensorControllerResponseCacheTest")
    static class HeaderProbeProvider extends BaseWindyDataProvider<WindyMeasurement> {

        static final String NAME = "probe";

        static final String TEST_HEADER_NAME = "X-Test-Header";

        static final String TEST_HEADER_VALUE = "sent";

        HeaderProbeProvider(@Named(NAME) WindDataProviderConfig config, ObjectMapper objectMapper) {
            super(config, objectMapper);
        }

        @Override
        public String getCallUrl(String sensorId) {
            return getUrl().formatted(sensorId);
        }

        @Override
        public Map<String, String> requestHeaders(String sensorId) {
            return Map.of(TEST_HEADER_NAME, TEST_HEADER_VALUE);
        }

        @Override
        public Try<List<SensorDataDTO>> extractTimedReadings(
                String sensorId, String response, int readingWindowSeconds, int numberOfReadings) {
            return Try.success(List.of(new SensorDataDTO(7.5f, 6.5f, 4.0f, 270.0f, 1_700_000_000L)));
        }

        @Override
        public SensorDataDTO mapToDTO(WindyMeasurement measurement) {
            throw new UnsupportedOperationException("the probe provider never deserializes a payload");
        }

        @Override
        public SensorDataDTO getLastReading(List<WindyMeasurement> data) {
            throw new UnsupportedOperationException("the probe provider never deserializes a payload");
        }
    }
}
