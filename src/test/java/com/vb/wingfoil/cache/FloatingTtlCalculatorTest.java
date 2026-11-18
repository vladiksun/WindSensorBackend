package com.vb.wingfoil.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * The expiry calculator decides how long every cache entry lives, so its bounds are asserted directly rather than
 * through timing-sensitive integration tests: band membership, the exact behaviour at a zero window, the one-second
 * floor, and the fact that consecutive draws differ — which is the whole point of a floating TTL.
 */
class FloatingTtlCalculatorTest {

    private static final int DRAWS = 5_000;

    private static FloatingTtlCalculator calculator(Duration ttl, Duration floatingWindow) {
        var properties = new SensorDataCacheProperties();
        properties.setTtl(ttl);
        properties.setFloatingWindow(floatingWindow);

        return new FloatingTtlCalculator(properties);
    }

    @Test
    void everyDrawStaysInsideTheConfiguredBand() {
        var ttl = Duration.ofMinutes(5);
        var window = Duration.ofMinutes(1);
        var calculator = calculator(ttl, window);
        var lower = ttl.minus(window).toMillis();
        var upper = ttl.plus(window).toMillis();

        for (var i = 0; i < DRAWS; i++) {
            var drawn = calculator.nextTtl().toMillis();

            assertTrue(
                    drawn >= lower && drawn <= upper,
                    "draw outside the ttl ± window band: " + drawn + " not in [" + lower + ", " + upper + ']');
        }
    }

    @Test
    void consecutiveEntriesDoNotShareOneExpiry() {
        var calculator = calculator(Duration.ofMinutes(5), Duration.ofMinutes(1));
        var distinctDraws = new HashSet<Long>();

        for (var i = 0; i < 200; i++) {
            distinctDraws.add(calculator.nextTtl().toMillis());
        }

        assertTrue(distinctDraws.size() > 100, "a fixed interval is exactly what the floating window must prevent");
    }

    @Test
    void drawsFallOnBothSidesOfTheBaseTtl() {
        var ttl = Duration.ofMinutes(5).toMillis();
        var calculator = calculator(Duration.ofMinutes(5), Duration.ofMinutes(1));
        var below = 0;
        var above = 0;

        for (var i = 0; i < DRAWS; i++) {
            var drawn = calculator.nextTtl().toMillis();
            if (drawn < ttl) {
                below++;
            } else if (drawn > ttl) {
                above++;
            }
        }

        assertTrue(below > DRAWS / 10, "the jitter is applied in either direction, not only forwards: " + below);
        assertTrue(above > DRAWS / 10, "the jitter is applied in either direction, not only backwards: " + above);
    }

    @Test
    void aZeroWindowExpiresEveryEntryExactlyOneBaseTtlAfterItWasStored() {
        var ttl = Duration.ofMinutes(5);
        var calculator = calculator(ttl, Duration.ZERO);

        for (var i = 0; i < 100; i++) {
            assertEquals(ttl.toMillis(), calculator.nextTtl().toMillis(), "window zero removes the floating behaviour");
        }

        var storedAt = 1_700_000_000_000L;
        assertEquals(storedAt + ttl.toMillis(), calculator.expiresAtEpochMillis(storedAt));
    }

    @Test
    void aWindowWiderThanTheTtlStillKeepsAtLeastOneSecond() {
        var calculator = calculator(Duration.ofSeconds(2), Duration.ofMinutes(1));

        for (var i = 0; i < DRAWS; i++) {
            assertTrue(
                    calculator.nextTtl().toMillis() >= FloatingTtlCalculator.MINIMUM_TTL.toMillis(),
                    "a wide window must not make an entry instantly stale");
        }
    }

    @Test
    void theDefaultConfigurationIsAFiveMinuteBaseWithAOneMinuteWindow() {
        var properties = new SensorDataCacheProperties();

        assertEquals(Duration.ofMinutes(5), properties.getTtl());
        assertEquals(Duration.ofMinutes(1), properties.getFloatingWindow());
        assertTrue(properties.isEnabled(), "the shipped default caches");
    }

    @Test
    void effectiveTtlClampsADrawIntoTheBandAndFloorsTheResult() {
        assertEquals(
                Duration.ofSeconds(8),
                FloatingTtlCalculator.effectiveTtl(Duration.ofSeconds(5), Duration.ofSeconds(3), 9_000));
        assertEquals(
                Duration.ofSeconds(2),
                FloatingTtlCalculator.effectiveTtl(Duration.ofSeconds(5), Duration.ofSeconds(3), -9_000));
        assertEquals(
                Duration.ofSeconds(1),
                FloatingTtlCalculator.effectiveTtl(Duration.ofSeconds(2), Duration.ofSeconds(10), -10_000));
        assertEquals(
                Duration.ofSeconds(5), FloatingTtlCalculator.effectiveTtl(Duration.ofSeconds(5), Duration.ZERO, 4_000));
        assertEquals(
                Duration.ofSeconds(5),
                FloatingTtlCalculator.effectiveTtl(Duration.ofSeconds(5), Duration.ofSeconds(3), 0L));
    }
}
