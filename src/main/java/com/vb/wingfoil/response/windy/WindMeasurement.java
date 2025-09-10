package com.vb.wingfoil.response.windy;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

@Serdeable(naming = SnakeCaseStrategy.class)
public record WindMeasurement(
    float windAvg,
    float windMin,
    float windMax,
    float windDirection,
    long timestamp,
    float pressure,
    float temperature,
    float humidity
) {
}
