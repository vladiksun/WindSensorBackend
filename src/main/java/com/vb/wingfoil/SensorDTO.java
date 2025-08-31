package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorDTO(Float windMax, Float windAvg, Float windMin, Float windDirection) {

    static SensorDTO empty() {
        return new SensorDTO(0F,0F,0F,0F);
    }

}
