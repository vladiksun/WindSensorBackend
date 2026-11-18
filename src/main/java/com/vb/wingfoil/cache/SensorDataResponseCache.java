package com.vb.wingfoil.cache;

import com.vb.wingfoil.SensorDataDTO;
import io.micronaut.cache.SyncCache;
import io.vavr.control.Option;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;
import org.ehcache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The short-lived response cache in front of the provider fetch. It is a separate Ehcache instance
 * ({@code ehcache.caches.sensor-data-cache} in {@code application.yml}) with a bounded heap tier and no disk tier:
 * an entry lives for minutes, so persisting one would only risk serving a reading that survived a restart.
 *
 * <p>Nothing here knows what a provider is. The cache sits in the shared request path, which is why every registered
 * provider — including one added later — is cached by this same code with no caching code of its own.
 */
@Singleton
public class SensorDataResponseCache {

    /** Name of the Ehcache instance backing {@code POST /sensor-data}. */
    public static final String CACHE_NAME = "sensor-data-cache";

    private static final Logger log = LoggerFactory.getLogger(SensorDataResponseCache.class);

    private final SyncCache<Cache> cache;

    private final FloatingTtlCalculator ttlCalculator;

    public SensorDataResponseCache(@Named(CACHE_NAME) SyncCache<Cache> cache, FloatingTtlCalculator ttlCalculator) {
        this.cache = cache;
        this.ttlCalculator = ttlCalculator;
    }

    /**
     * Identity of a cached response: {@code <provider>/<sensorId>/<window>/<count>}. Providers never share an entry,
     * and a different window or number of readings is a different entry because those values change the response body.
     */
    public static String cacheKey(
            String providerName, String sensorId, int readingWindowSeconds, int numberOfReadings) {
        return providerName + "/" + sensorId + "/" + readingWindowSeconds + "/" + numberOfReadings;
    }

    /**
     * The stored entry for {@code key}, if it is still valid. An expired entry counts as absent and is removed here:
     * a lookup must never extend an entry's life, and eager removal keeps the bounded heap from filling with readings
     * nobody will ask for again.
     */
    public Option<CachedSensorReadings> lookup(String key) {
        var stored = cache.get(key, CachedSensorReadings.class);
        if (stored.isEmpty()) {
            return Option.none();
        }

        var entry = stored.get();
        if (!entry.isValidAt(System.currentTimeMillis())) {
            cache.invalidate(key);
            log.debug("Evicted expired sensor-data entry {} (expired at {})", key, entry.expiresAtEpochMillis());

            return Option.none();
        }

        return Option.some(entry);
    }

    /**
     * Store freshly fetched readings under {@code key} with a freshly drawn expiry, and return what was stored.
     * Only successful provider calls reach this method — a failure stores nothing.
     */
    public CachedSensorReadings store(String key, List<SensorDataDTO> readings) {
        var storedAt = System.currentTimeMillis();
        var entry = new CachedSensorReadings(readings, storedAt, ttlCalculator.expiresAtEpochMillis(storedAt));

        cache.put(key, entry);

        return entry;
    }
}
