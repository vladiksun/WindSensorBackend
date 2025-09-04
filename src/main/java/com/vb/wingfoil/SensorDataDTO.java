package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorDataDTO(Float windMax, Float windAvg, Float windMin, Float windDirection, Float temperature) {

    static SensorDataDTO empty() {
        return new SensorDataDTO(0F,0F,0F,0F, 0F);
    }

}
