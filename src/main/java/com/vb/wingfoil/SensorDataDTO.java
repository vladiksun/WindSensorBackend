package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;
import java.io.Serializable;

/**
 * A single wind reading as the API reports it. {@code Serializable} because a parsed list of these is what the
 * {@code sensor-data-cache} Ehcache entry holds, and that cache only accepts serializable values.
 */
@Serdeable
public record SensorDataDTO(float windMax, float windAvg, float windMin, float windDirection, long timestamp)
        implements Serializable {

    public static SensorDataDTO empty() {
        return new SensorDataDTO(0F, 0F, 0F, 0F, 0);
    }
}
