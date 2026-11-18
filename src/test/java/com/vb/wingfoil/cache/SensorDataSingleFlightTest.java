package com.vb.wingfoil.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.SensorDataDTO;
import io.vavr.control.Try;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Coalescing is what stops an expiry from becoming a burst of identical upstream calls, so these tests assert the
 * three properties that matter: one supplier call per key, no coupling between different keys, and a failure that
 * reaches every participant as the throwable it was — never as a different error.
 */
class SensorDataSingleFlightTest {

    private static final Duration WAIT_BUDGET = Duration.ofSeconds(20);

    /** Long enough for every participant to have registered before the leader's fetch can finish. */
    private static final Duration SLOW_FETCH_MILLIS = Duration.ofMillis(600);

    private static List<SensorDataDTO> readings(float windMax) {
        return List.of(new SensorDataDTO(windMax, windMax, windMax, 180.0f, 1_700_000_000L));
    }

    @Test
    void concurrentIdenticalKeysProduceOneSupplierCallAndEqualResults() throws Exception {
        var flight = new SensorDataSingleFlight();
        var supplierCalls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var results = new ArrayList<Try<List<SensorDataDTO>>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Try<List<SensorDataDTO>>>>();
            for (var i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    return flight.run("windy/12345/3600/5", WAIT_BUDGET, () -> {
                        supplierCalls.incrementAndGet();
                        sleepQuietly(SLOW_FETCH_MILLIS);

                        return Try.success(readings(7.5f));
                    });
                }));
            }
            start.countDown();

            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, supplierCalls.get(), "identical keys must coalesce into exactly one fetch");
        for (var result : results) {
            assertTrue(result.isSuccess(), "every participant gets the leader's readings");
            assertEquals(readings(7.5f), result.get());
        }
        assertEquals(0, flight.inFlightKeys(), "the leader must remove its own future when the fetch ends");
    }

    @Test
    void aFailureReachesEveryParticipantAsTheSameThrowable() throws Exception {
        var flight = new SensorDataSingleFlight();
        var failure = new IOException("upstream refused");
        var supplierCalls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var results = new ArrayList<Try<List<SensorDataDTO>>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Try<List<SensorDataDTO>>>>();
            for (var i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    return flight.run("windy/12345/3600/5", WAIT_BUDGET, () -> {
                        supplierCalls.incrementAndGet();
                        sleepQuietly(SLOW_FETCH_MILLIS);

                        return Try.failure(failure);
                    });
                }));
            }
            start.countDown();

            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, supplierCalls.get(), "a failing fetch is coalesced exactly like a successful one");
        for (var result : results) {
            assertTrue(result.isFailure(), "a coalesced failure must not be turned into an empty success");
            assertSame(failure, result.getCause(), "waiters fail with the identical throwable, not a rewrapped one");
        }
    }

    @Test
    void requestsForDifferentKeysDoNotWaitOnEachOther() throws Exception {
        var flight = new SensorDataSingleFlight();
        var slowFetchStarted = new CountDownLatch(1);
        var releaseSlowFetch = new CountDownLatch(1);
        var slowFetchFinishedAt = new AtomicLong();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slowCall = executor.submit(() -> flight.run("windy/slow/3600/5", WAIT_BUDGET, () -> {
                slowFetchStarted.countDown();
                awaitQuietly(releaseSlowFetch);
                slowFetchFinishedAt.set(System.nanoTime());

                return Try.success(readings(1.0f));
            }));

            assertTrue(slowFetchStarted.await(10, TimeUnit.SECONDS), "the leader for the slow key must have started");

            // Key B is free: it has to run its own fetch and return while key A is still held open. If keys shared
            // one gate — or one lock around the map — this call would block until the latch below is released.
            var fastCall = executor.submit(() -> flight.run("windy/fast/3600/5", WAIT_BUDGET, () -> {
                assertTrue(slowFetchFinishedAt.get() == 0L, "the slow key must still be in flight");

                return Try.success(readings(2.0f));
            }));

            var fastResult = fastCall.get(5, TimeUnit.SECONDS);
            assertTrue(fastResult.isSuccess());
            assertEquals(readings(2.0f), fastResult.get());

            releaseSlowFetch.countDown();
            assertTrue(
                    slowCall.get(10, TimeUnit.SECONDS).isSuccess(), "the held fetch still completes for its own key");
        }

        assertEquals(0, flight.inFlightKeys());
    }

    @Test
    void aWaiterThatRunsOutOfPatienceFailsOnItsOwnInsteadOfBlockingForever() throws Exception {
        var flight = new SensorDataSingleFlight();
        var slowFetchStarted = new CountDownLatch(1);
        var releaseSlowFetch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var leader = executor.submit(() -> flight.run("windy/stuck/3600/5", WAIT_BUDGET, () -> {
                slowFetchStarted.countDown();
                awaitQuietly(releaseSlowFetch);

                return Try.success(readings(1.0f));
            }));

            assertTrue(slowFetchStarted.await(10, TimeUnit.SECONDS));

            var impatient = executor.submit(
                    () -> flight.run("windy/stuck/3600/5", Duration.ofMillis(200), () -> Try.success(readings(9.0f))));

            var result = impatient.get(10, TimeUnit.SECONDS);
            assertTrue(result.isFailure(), "a bounded wait must end in a failure rather than an endless block");
            assertNotNull(result.getCause());

            releaseSlowFetch.countDown();
            assertTrue(
                    leader.get(10, TimeUnit.SECONDS).isSuccess(), "the leader is not affected by a waiter giving up");
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
