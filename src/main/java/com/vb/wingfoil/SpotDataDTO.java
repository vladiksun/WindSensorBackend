package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record SpotDataDTO(
        String location,
        Integer readingWindow,
        Integer numberOfReadings,
        List<SensorDTO> sensors
) {
}
