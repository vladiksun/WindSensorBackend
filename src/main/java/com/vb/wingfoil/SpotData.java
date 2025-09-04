package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record SpotData(String location, List<Sensor> sensors) {
}
