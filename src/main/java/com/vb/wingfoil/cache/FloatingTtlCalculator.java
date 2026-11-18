package com.vb.wingfoil.cache;

import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives every cache entry its own expiry, drawn from the band around the configured base TTL.
 *
 * <p>At store time the calculator draws a jitter whose magnitude is uniform over {@code [0, floating window]} and
 * applies it to the base TTL with a random sign, so an entry stays valid for somewhere within {@code ttl ± window}
 * but never for less than one second. The draw happens once per stored entry — never once per process — which is
 * the point: refetch instants stop being the fixed interval that a periodic poller is recognised by.
 *
 * <p>Why not Ehcache's own expiry: {@code Expirations.timeToLive(Duration)} is fixed per cache instance and
 * micronaut-cache exposes no per-entry TTL knob, so a random offset cannot be expressed in configuration at all.
 * The expiry therefore travels inside the stored value (the pattern {@code CachedTile} already uses here), and the
 * arithmetic lives in this testable component rather than in a cache-engine extension point.
 */
@Singleton
public class FloatingTtlCalculator {

    /** Floor applied to every draw so a window wider than the TTL cannot make an entry instantly stale. */
    static final Duration MINIMUM_TTL = Duration.ofSeconds(1);

    private final SensorDataCacheProperties properties;

    public FloatingTtlCalculator(SensorDataCacheProperties properties) {
        this.properties = properties;
    }

    /** Validity of the next stored entry: {@code ttl ± jitter}, jitter uniform over {@code [0, floating window]}. */
    public Duration nextTtl() {
        var window = properties.getFloatingWindow();

        return effectiveTtl(properties.getTtl(), window, drawJitterMillis(window));
    }

    /** End of the validity window of an entry stored at {@code storedAtEpochMillis}. */
    public long expiresAtEpochMillis(long storedAtEpochMillis) {
        return storedAtEpochMillis + nextTtl().toMillis();
    }

    /**
     * The arithmetic alone, with the draw supplied by the caller: a jitter outside the band is clamped into it, and
     * the result is floored at {@link #MINIMUM_TTL}. Kept static and side-effect-free so tests can assert exact
     * outcomes (a zero window, the floor) without reaching into randomness.
     */
    static Duration effectiveTtl(Duration baseTtl, Duration window, long jitterMillis) {
        var windowMillis = Math.max(0L, window.toMillis());
        var boundedJitter = Math.clamp(jitterMillis, -windowMillis, windowMillis);

        return Duration.ofMillis(Math.max(MINIMUM_TTL.toMillis(), baseTtl.toMillis() + boundedJitter));
    }

    /** One draw: magnitude uniform over {@code [0, window]}, sign chosen at random. */
    static long drawJitterMillis(Duration window) {
        var windowMillis = Math.max(0L, window.toMillis());
        if (windowMillis == 0L) {
            return 0L;
        }

        var magnitude = ThreadLocalRandom.current().nextLong(windowMillis + 1);

        return ThreadLocalRandom.current().nextBoolean() ? -magnitude : magnitude;
    }
}
