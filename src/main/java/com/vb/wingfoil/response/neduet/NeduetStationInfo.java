package com.vb.wingfoil.response.neduet;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record NeduetStationInfo(
    @NonNull String name,
    @NonNull String id,
    @NonNull List<NeduetMeasurement> data
) {
}
