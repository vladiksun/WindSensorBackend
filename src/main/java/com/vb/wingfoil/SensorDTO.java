package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record SensorDTO(
        String id,
        String provider,
        String label,
        Integer readingWindow,
        Integer numberOfReadings,
        List<TileDTO> tiles) {}
