package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;

import java.util.List;

@FunctionalInterface
public interface IntervalReader<M> {
    List<SensorDataDTO> apply(List<M> data, int readingWindowSeconds, int numberOfReadings);
}
