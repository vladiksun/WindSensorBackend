package com.vb.wingfoil.cache;

import com.vb.wingfoil.SensorDataDTO;
import io.vavr.control.Try;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coalesces concurrent requests that resolve to the same cache key, so an expiry does not turn into a burst of
 * identical upstream calls: {@code putIfAbsent} elects one leader, only the leader runs the fetch, and every other
 * request joins that call and receives its outcome — the same readings on success, the identical throwable on failure.
 *
 * <p>Keys are independent: a waiter is registered under its own key only, so a slow provider for one sensor cannot
 * hold another sensor's request. And because the map is keyed rather than guarded by one lock, nothing blocks inside
 * {@code computeIfAbsent} — a blocking HTTP call there would hold a map bin against unrelated keys.
 */
@Singleton
public class SensorDataSingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SensorDataSingleFlight.class);

    private final ConcurrentHashMap<String, CompletableFuture<List<SensorDataDTO>>> inFlight =
            new ConcurrentHashMap<>();

    /**
     * Run {@code fetch} for {@code key}, or join the fetch another request already started for that key.
     *
     * @param waitBudget how long a waiter may block on the leader's call before failing on its own; callers size it
     *        over the bounds that call is itself confined by, so a wedged leader cannot pin virtual threads
     * @return the fetched readings, or the failure of whichever call produced them
     */
    public Try<List<SensorDataDTO>> run(String key, Duration waitBudget, Supplier<Try<List<SensorDataDTO>>> fetch) {
        var own = new CompletableFuture<List<SensorDataDTO>>();
        var alreadyRunning = inFlight.putIfAbsent(key, own);
        if (alreadyRunning != null) {
            log.debug("Joining the in-flight sensor-data fetch for {}", key);

            return await(alreadyRunning, key, waitBudget);
        }

        try {
            var attempt = fetch.get();
            if (attempt.isSuccess()) {
                own.complete(attempt.get());
            } else {
                own.completeExceptionally(attempt.getCause());
            }

            return attempt;
        } catch (RuntimeException unexpected) {
            own.completeExceptionally(unexpected);

            return Try.failure(unexpected);
        } finally {
            // Only the future this call installed, so a later attempt is never clobbered by an older leader.
            inFlight.remove(key, own);
        }
    }

    /** Number of keys currently being fetched; exposed for tests and diagnostics. */
    public int inFlightKeys() {
        return inFlight.size();
    }

    private Try<List<SensorDataDTO>> await(
            CompletableFuture<List<SensorDataDTO>> running, String key, Duration waitBudget) {
        try {
            return Try.success(running.get(waitBudget.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            log.warn("Timed out waiting for the in-flight sensor-data fetch for {}", key, e);

            return Try.failure(new IOException("Timed out waiting for the in-flight sensor data fetch for " + key, e));
        } catch (ExecutionException e) {
            // The leader's own throwable, unchanged: a coalesced failure fails exactly as this request's own call
            // would.
            var cause = e.getCause() != null ? e.getCause() : e;

            return Try.failure(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return Try.failure(
                    new IOException("Interrupted while waiting for the in-flight sensor data fetch for " + key, e));
        }
    }
}
