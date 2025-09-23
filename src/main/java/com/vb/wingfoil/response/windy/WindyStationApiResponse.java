package com.vb.wingfoil.response.windy;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

@Serdeable(naming = SnakeCaseStrategy.class)
public record WindyStationApiResponse(
    @NonNull String status,
    @NonNull WindyResponse response,
    long time
) {
}
