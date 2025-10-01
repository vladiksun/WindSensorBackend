package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SensorRequestDTO(Integer readingWindow,
                               Integer numberOfReadings,
                               SensorDTO sensor
) {
}
