package com.vb.wingfoil.response.windy;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

@Serdeable(naming = SnakeCaseStrategy.class)
public record StationInfo(
    @NonNull String name,
    @NonNull String tzName
) {
}
