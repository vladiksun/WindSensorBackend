package com.vb.wingfoil;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.cache.SensorDataOutcome;
import com.vb.wingfoil.cache.SensorDataResponseCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vavr.control.Option;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The switch working the other way. Caching is off in the test profile, which is exactly the state an operator gets
 * from {@code SENSOR_DATA_CACHE_ENABLED=false}: every request must reach the provider on its own, with nothing
 * stored and nothing shared between requests. That is what the pre-existing /sensor-data scenarios rely on, so it
 * is asserted here rather than left to chance.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensorDataCachingDisabledTest implements TestPropertyProvider {

    private static StubUpstreamServer upstream;

    private ProxyService proxyService;

    private SensorDataResponseCache responseCache;

    @Override
    public @NonNull Map<String, String> getProperties() {
        if (upstream == null) {
            try {
                upstream = new StubUpstreamServer();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the stub upstream", e);
            }
        }

        // No sensor-data-cache keys here: application-test.yml leaves caching disabled, as it does for every other
        // /sensor-data suite in this project.
        return Map.of(
                "spec.name",
                "SensorDataCachingDisabledTest",
                "wind-sensor.wind-providers.windy.url",
                upstream.windyUrlTemplate());
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.close();
            upstream = null;
        }
    }

    @BeforeEach
    void resetRecords(ApplicationContext ctx) {
        this.proxyService = ctx.getBean(ProxyService.class);
        this.responseCache = ctx.getBean(SensorDataResponseCache.class);

        upstream.resetRecords();
    }

    @Test
    void everyRequestStillReachesTheProviderOnceWhenCachingIsOff(RequestSpecification spec) {
        postSensorData(spec, "12345")
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                .body("size()", is(2));
        postSensorData(spec, "12345")
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS")
                .body("size()", is(2));

        assertEquals(
                2, upstream.callsFor("/api/windy/12345"), "one client request, one provider call — as before caching");
        assertTrue(
                responseCache
                        .lookup(SensorDataResponseCache.cacheKey("windy", "12345", 3600, 5))
                        .isEmpty(),
                "and nothing was stored that a later request could answer with");
    }

    @Test
    void concurrentIdenticalRequestsDoNotShareOneFetchWhenCachingIsOff() throws Exception {
        var futures = new ArrayList<Future<SensorDataOutcome>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> proxyService
                        .requestTimedReadings(
                                Option.none(), Option.none(), new SensorDTO("slow", "windy", null, null, null, null))
                        .get()));
            }

            for (var future : futures) {
                assertEquals(2, future.get(20, TimeUnit.SECONDS).readings().size());
            }
        }

        assertEquals(6, upstream.callsFor("/api/windy/slow"), "without the cache there is no coalescing either");
    }

    private static Response postSensorData(RequestSpecification spec, String sensorId) {
        String body = """
                {
                  "readingWindow": 3600,
                  "numberOfReadings": 5,
                  "sensor": {
                    "id": "%s",
                    "provider": "windy"
                  }
                }
                """.formatted(sensorId);

        return spec.contentType(ContentType.JSON).body(body).when().post("/sensor-data");
    }
}
