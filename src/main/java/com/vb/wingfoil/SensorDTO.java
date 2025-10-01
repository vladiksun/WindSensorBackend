package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorDTO(
        String id,
        String provider,
        String label,
        Integer readingWindow,
        Integer numberOfReadings
) {
}
