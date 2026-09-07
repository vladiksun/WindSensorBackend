package com.vb.wingfoil.response.windy;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

@Serdeable(naming = SnakeCaseStrategy.class)
public record WindyMeasurement(
        Float windAvg,
        Float windMin,
        Float windMax,
        Float windDirection,
        long timestamp,
        Float pressure,
        Float temperature,
        Float humidity) {}
