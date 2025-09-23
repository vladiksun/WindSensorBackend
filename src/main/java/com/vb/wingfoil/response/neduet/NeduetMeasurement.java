package com.vb.wingfoil.response.neduet;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record NeduetMeasurement(
    float avr,
    float min,
    float max,
    float dir,
    long timestamp
) {
}
