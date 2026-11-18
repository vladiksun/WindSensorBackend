package com.vb.wingfoil.cache;

import com.vb.wingfoil.SensorDataDTO;
import java.io.Serializable;
import java.util.List;

/**
 * A cached {@code POST /sensor-data} response: the already windowed and reduced readings, plus the validity
 * window that travels with them because Ehcache 3 TTL is a cache-level setting and this cache needs a different
 * expiry per entry.
 *
 * <p>Validity is time-to-live, not time-to-idle: a lookup never extends {@code expiresAtEpochMillis}, so a hot
 * poll cannot pin an entry forever. The readings are copied on construction so a later mutation of the list a
 * provider returned cannot rewrite what is already cached.
 *
 * <p>{@code Serializable} for the same reason {@code CachedTile} is: the Ehcache instance behind Micronaut's cache
 * abstraction validates every value against {@code Serializable} when it writes one, and a value that fails that
 * check makes the write throw — a heap-only cache with no disk tier is not exempt. The payload is a list of records
 * of primitives, so the entry round-trips through Java serialization with no serializer of its own.
 *
 * @param readings             the response body this entry answers with, identical to what the fetch produced
 * @param storedAtEpochMillis  when the entry was written
 * @param expiresAtEpochMillis end of the validity window drawn from the configured TTL band
 */
public record CachedSensorReadings(List<SensorDataDTO> readings, long storedAtEpochMillis, long expiresAtEpochMillis)
        implements Serializable {

    public CachedSensorReadings {
        readings = List.copyOf(readings);
    }

    /** Whether this entry may still answer a request at {@code epochMillis}. */
    public boolean isValidAt(long epochMillis) {
        return expiresAtEpochMillis > epochMillis;
    }
}
