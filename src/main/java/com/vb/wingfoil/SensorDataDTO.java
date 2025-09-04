package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorDataDTO(Float windMax, Float windAvg, Float windMin, Float windDirection) {

    static SensorDataDTO empty() {
        return new SensorDataDTO(0F,0F,0F,0F);
    }

}
