package com.vb.wingfoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.cache.CacheStatus;
import com.vb.wingfoil.cache.SensorDataOutcome;
import com.vb.wingfoil.cache.SensorDataResponseCache;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.vavr.control.Option;
import io.vavr.control.Try;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.ehcache.Cache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Coalescing and entry identity asserted through {@link ProxyService} against the stub upstream, because both are
 * properties of the request path rather than of the HTTP response: what a burst of identical requests costs the
 * provider, and which entry a request belongs to when a sensor pins its own window.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensorDataCoalescingTest implements TestPropertyProvider {

    private static StubUpstreamServer upstream;

    private ProxyService proxyService;

    private SensorDataResponseCache responseCache;

    private SyncCache<Cache> ehcache;

    @Override
    public @NonNull Map<String, String> getProperties() {
        if (upstream == null) {
            try {
                upstream = new StubUpstreamServer();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the stub upstream", e);
            }
        }

        return Map.of(
                "spec.name",
                "SensorDataCoalescingTest",
                "wind-sensor.wind-providers.windy.url",
                upstream.windyUrlTemplate(),
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
        this.proxyService = ctx.getBean(ProxyService.class);
        this.responseCache = ctx.getBean(SensorDataResponseCache.class);
        this.ehcache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(SensorDataResponseCache.CACHE_NAME))
                        .orElseThrow();

        ehcache.invalidateAll();
        upstream.resetRecords();
    }

    private static SensorDTO sensor(String id, Integer readingWindow, Integer numberOfReadings) {
        return new SensorDTO(id, "windy", null, readingWindow, numberOfReadings, null);
    }

    @Test
    void aBurstOfIdenticalConcurrentRequestsCostsTheProviderOneCall() throws Exception {
        var outcomes = runConcurrently(
                6, () -> proxyService.requestTimedReadings(Option.none(), Option.none(), sensor("slow", null, null)));

        assertEquals(
                1, upstream.callsFor("/api/windy/slow"), "an expiry must not turn into a burst of identical calls");
        for (var outcome : outcomes) {
            assertTrue(outcome.isSuccess(), "every joined request gets the leader's readings");
            assertEquals(2, outcome.get().readings().size());
        }

        assertEquals(
                outcomes.getFirst().get().readings(), outcomes.getLast().get().readings());
    }

    @Test
    void oneSlowSensorDoesNotDelayARequestForAnother() throws Exception {
        var slowFinishedAt = new AtomicLong();
        var fastFinishedAt = new AtomicLong();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slowCall = executor.submit(
                    () -> proxyService.requestTimedReadings(Option.none(), Option.none(), sensor("slow", null, null)));
            var fastCall = executor.submit(
                    () -> proxyService.requestTimedReadings(Option.none(), Option.none(), sensor("12345", null, null)));

            // The slow fetch holds its own key open for most of a second. A gate shared across keys — or one lock
            // around the in-flight map — would make the fast request finish after it instead of before it.
            var fastOutcome = fastCall.get(20, TimeUnit.SECONDS);
            fastFinishedAt.set(System.nanoTime());
            var slowOutcome = slowCall.get(20, TimeUnit.SECONDS);
            slowFinishedAt.set(System.nanoTime());

            assertTrue(fastOutcome.isSuccess() && slowOutcome.isSuccess());
            assertTrue(
                    fastFinishedAt.get() < slowFinishedAt.get(),
                    "the slow sensor must not hold another sensor's request");
        }

        assertEquals(1, upstream.callsFor("/api/windy/slow"));
        assertEquals(1, upstream.callsFor("/api/windy/12345"));
    }

    @Test
    void aSensorPinningItsOwnWindowOwnsAnEntryApartFromTheSameIdRequestedWithoutIt() {
        // The sensor's own configuration decides the effective window, so that is what identifies the entry.
        var pinned = proxyService
                .requestTimedReadings(Option.none(), Option.none(), sensor("cfg", 7200, null))
                .get();
        assertEquals(CacheStatus.MISS, pinned.status());
        assertTrue(
                responseCache
                        .lookup(SensorDataResponseCache.cacheKey("windy", "cfg", 7200, 5))
                        .isDefined(),
                "the entry carries the effective value the sensor configuration produced");

        // The same id without that configuration and with an explicit window is a different combination.
        var explicit = proxyService
                .requestTimedReadings(Option.of(3600), Option.none(), sensor("cfg", null, null))
                .get();
        assertEquals(CacheStatus.MISS, explicit.status());
        assertTrue(responseCache
                .lookup(SensorDataResponseCache.cacheKey("windy", "cfg", 3600, 5))
                .isDefined());

        assertEquals(2, upstream.callsFor("/api/windy/cfg"), "two combinations, two fetches");

        assertEquals(
                CacheStatus.HIT,
                proxyService
                        .requestTimedReadings(Option.none(), Option.none(), sensor("cfg", 7200, null))
                        .get()
                        .status());
        assertEquals(
                CacheStatus.HIT,
                proxyService
                        .requestTimedReadings(Option.of(3600), Option.none(), sensor("cfg", null, null))
                        .get()
                        .status());

        assertEquals(2, upstream.callsFor("/api/windy/cfg"), "both entries answered on their own afterwards");
    }

    /** Runs {@code attempts} identical calls at once and hands back every outcome in submission order. */
    private static List<Try<SensorDataOutcome>> runConcurrently(int attempts, Callable<Try<SensorDataOutcome>> attempt)
            throws Exception {
        var outcomes = new ArrayList<Try<SensorDataOutcome>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Try<SensorDataOutcome>>>();
            for (var i = 0; i < attempts; i++) {
                futures.add(executor.submit(attempt));
            }

            for (var future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
        }

        return outcomes;
    }

    @Test
    void aFailureIsSharedByEveryRequestThatJoinedItWithoutBeingRetried() throws Exception {
        var outcomes = runConcurrently(
                6,
                () -> proxyService.requestTimedReadings(
                        Option.none(), Option.none(), sensor(StubUpstreamServer.UNAVAILABLE_SENSOR_ID, null, null)));

        assertEquals(1, upstream.callsFor("/api/windy/unavailable"), "a coalesced failure is not retried per request");
        for (var outcome : outcomes) {
            assertTrue(outcome.isFailure(), "the failure reaches every joined request");
            assertNotNull(outcome.getCause());
            assertTrue(
                    responseCache
                            .lookup(SensorDataResponseCache.cacheKey(
                                    "windy", StubUpstreamServer.UNAVAILABLE_SENSOR_ID, 3600, 5))
                            .isEmpty(),
                    "and nothing was stored from it");
        }
    }

    /**
     * What that shared failure actually is: the mapped application failure — carrying the status the provider itself
     * answered with and a client-safe message — rather than the raw upstream exception, and one identical instance for
     * every request that joined the call. The original upstream failure survives as its cause, which is what keeps the
     * server-side diagnosis (status, URL, stack) available even though the client sees neither.
     */
    @Test
    void aCoalescedFailureCarriesTheMappedStatusAndKeepsTheOriginalUpstreamCause() throws Exception {
        var outcomes = runConcurrently(
                4,
                () -> proxyService.requestTimedReadings(
                        Option.none(), Option.none(), sensor(StubUpstreamServer.UNAVAILABLE_SENSOR_ID, null, null)));

        assertEquals(1, upstream.callsFor("/api/windy/unavailable"), "one upstream call, one mapped failure");

        AppException shared = null;
        for (var outcome : outcomes) {
            assertTrue(outcome.isFailure(), "every joined request fails");
            assertInstanceOf(AppException.class, outcome.getCause());

            var failure = (AppException) outcome.getCause();
            assertEquals(503, failure.statusCode(), "the provider's own status is what every request reports");
            assertEquals("Sensor data provider returned an error", failure.getMessage());
            assertFalse(failure.getMessage().contains("127.0.0.1"), "no provider host in the client-facing message");

            var originalCause = failure.getCause();
            assertInstanceOf(IOException.class, originalCause, "the upstream failure is preserved as the cause");
            assertTrue(
                    originalCause.getMessage().contains("status 503"),
                    "and it still names what happened upstream: " + originalCause.getMessage());

            if (shared == null) {
                shared = failure;
            } else {
                assertSame(shared, failure, "coalesced waiters are handed the very same exception");
            }
        }
    }

    /** A refusal is an upstream status too: it maps to 403 rather than to a gateway failure. */
    @Test
    void aForbiddenUpstreamAnswerMapsToForbiddenAndNotToBadGateway() {
        var outcome = proxyService.requestTimedReadings(
                Option.none(), Option.none(), sensor(StubUpstreamServer.FORBIDDEN_SENSOR_ID, null, null));

        assertTrue(outcome.isFailure());
        assertInstanceOf(AppException.class, outcome.getCause());

        var failure = (AppException) outcome.getCause();
        assertEquals(403, failure.statusCode());
        assertTrue(responseCache
                .lookup(SensorDataResponseCache.cacheKey("windy", StubUpstreamServer.FORBIDDEN_SENSOR_ID, 3600, 5))
                .isEmpty());
        assertInstanceOf(IOException.class, failure.getCause(), "the provider's refusal stays the cause");
    }
}
