package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorDataDTO(
        float windMax,
        float windAvg,
        float windMin,
        float windDirection,
        long timestamp
) {

    public static SensorDataDTO empty() {
        return new SensorDataDTO(0F,0F,0F,0F, 0);
    }

}
