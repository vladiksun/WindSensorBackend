package com.vb.wingfoil.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vb.wingfoil.SensorDataDTO;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.ehcache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The response cache component against the real Ehcache instance declared for it: key identity, what a hit returns,
 * and the two rules the expiry depends on — an expired entry never answers a request but is removed when found, and
 * a lookup never lengthens an entry's life.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensorDataResponseCacheTest {

    private static final Duration SHIPPED_TTL = Duration.ofMinutes(5);

    private static final Duration SHIPPED_WINDOW = Duration.ofMinutes(1);

    private SensorDataResponseCache responseCache;

    private SyncCache<Cache> ehcache;

    // Lookup from the context rather than constructor injection: the cache bean has to be qualified by name, and
    // this mirrors how the tile-cache tests reach their own named cache.
    @BeforeEach
    void initBeans(ApplicationContext ctx) {
        this.responseCache = ctx.getBean(SensorDataResponseCache.class);
        this.ehcache =
                (SyncCache<Cache>) ctx.findBean(SyncCache.class, Qualifiers.byName(SensorDataResponseCache.CACHE_NAME))
                        .orElseThrow();

        ehcache.invalidateAll();
    }

    private static List<SensorDataDTO> readings(float windMax) {
        return List.of(new SensorDataDTO(windMax, windMax, windMax, 180.0f, 1_700_000_000L));
    }

    @Test
    void cacheKeyCarriesProviderSensorWindowAndCountInOrder() {
        var key = SensorDataResponseCache.cacheKey("windy", "0264", 3600, 5);

        assertEquals("windy/0264/3600/5", key);
        assertNotEquals(key, SensorDataResponseCache.cacheKey("onechip", "0264", 3600, 5), "providers never share");
        assertNotEquals(key, SensorDataResponseCache.cacheKey("windy", "0265", 3600, 5), "sensors never share");
        assertNotEquals(key, SensorDataResponseCache.cacheKey("windy", "0264", 7200, 5), "windows never share");
        assertNotEquals(key, SensorDataResponseCache.cacheKey("windy", "0264", 3600, 2), "counts never share");
    }

    @Test
    void entriesForDifferentProvidersSensorsWindowsAndCountsStaySeparate() {
        responseCache.store("windy/0264/3600/5", readings(1.0f));
        responseCache.store("onechip/0264/3600/5", readings(2.0f));
        responseCache.store("windy/0265/3600/5", readings(3.0f));
        responseCache.store("windy/0264/7200/2", readings(4.0f));

        assertEquals(
                readings(1.0f), responseCache.lookup("windy/0264/3600/5").get().readings());
        assertEquals(
                readings(2.0f),
                responseCache.lookup("onechip/0264/3600/5").get().readings());
        assertEquals(
                readings(3.0f), responseCache.lookup("windy/0265/3600/5").get().readings());
        assertEquals(
                readings(4.0f), responseCache.lookup("windy/0264/7200/2").get().readings());

        assertTrue(
                responseCache.lookup("windy/0264/3600/6").isEmpty(), "a parameter combination never stored is absent");
    }

    @Test
    void aHitReturnsExactlyTheReadingsTheFetchStored() {
        var fetched = List.of(
                new SensorDataDTO(11.0f, 10.0f, 9.0f, 200.0f, 1_700_000_000L),
                new SensorDataDTO(12.5f, 11.5f, 10.5f, 210.0f, 1_700_003_600L));

        var stored = responseCache.store("windy/7/3600/5", fetched);
        var hit = responseCache.lookup("windy/7/3600/5");

        assertTrue(hit.isDefined());
        assertEquals(fetched, hit.get().readings(), "a cached answer is byte-for-byte what the provider produced");
        assertEquals(stored.readings(), hit.get().readings());
    }

    @Test
    void anExpiredEntryCountsAsAbsentAndIsRemovedOnLookup() {
        var past = System.currentTimeMillis() - 1_000L;
        ehcache.put("windy/expired/3600/5", new CachedSensorReadings(readings(5.0f), past, past));

        assertTrue(
                responseCache.lookup("windy/expired/3600/5").isEmpty(), "an expired entry must never answer a request");
        assertTrue(
                ehcache.get("windy/expired/3600/5", CachedSensorReadings.class).isEmpty(),
                "a found-but-expired entry is removed instead of occupying heap");
    }

    @Test
    void everyStoredEntryGetsItsOwnExpiryInsideTheConfiguredBand() {
        var lower = SHIPPED_TTL.minus(SHIPPED_WINDOW).toMillis();
        var upper = SHIPPED_TTL.plus(SHIPPED_WINDOW).toMillis();
        var distinctDeltas = new HashSet<Long>();

        for (var i = 0; i < 25; i++) {
            var entry = responseCache.store("windy/sensor" + i + "/3600/5", readings(i));
            var validity = entry.expiresAtEpochMillis() - entry.storedAtEpochMillis();

            assertTrue(validity >= lower && validity <= upper, "entry stored for " + validity + "ms outside the band");
            distinctDeltas.add(validity);
        }

        assertTrue(distinctDeltas.size() > 10, "entries stored back to back must not expire at one repeated instant");
    }

    @Test
    void aLookupNeverExtendsHowLongAnEntryStaysValid() {
        var entry = responseCache.store("windy/hot/3600/5", readings(3.0f));

        for (var i = 0; i < 10; i++) {
            var hit = responseCache.lookup("windy/hot/3600/5").get();

            assertEquals(entry.storedAtEpochMillis(), hit.storedAtEpochMillis());
            assertEquals(
                    entry.expiresAtEpochMillis(),
                    hit.expiresAtEpochMillis(),
                    "validity is time-to-live, not time-to-idle");
        }
    }
}
