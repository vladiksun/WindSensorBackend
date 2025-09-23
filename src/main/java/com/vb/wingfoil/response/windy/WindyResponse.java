package com.vb.wingfoil.response.windy;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

@Serdeable(naming = SnakeCaseStrategy.class)
public record WindyResponse(
    @NonNull WindyStationInfo info,
    @NonNull List<WindyMeasurement> data
) {
}
