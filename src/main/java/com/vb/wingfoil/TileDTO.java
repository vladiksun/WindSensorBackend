package com.vb.wingfoil;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record TileDTO(String tileLabel, Double latitude, Double longitude) {}
