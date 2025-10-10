package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import com.vb.wingfoil.provider.WindDataProvider;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public abstract class BaseWindyDataProvider<T> implements WindDataProvider<T> {

    private final String name;

    private final String url;

    protected final ObjectMapper objectMapper;

    protected BaseWindyDataProvider(WindDataProviderConfig windDataProviderConfig, ObjectMapper objectMapper) {
        this.url = windDataProviderConfig.getUrl();
        this.name = windDataProviderConfig.getName();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUrl() {
        return url;
    }

    protected <M> Try<List<SensorDataDTO>> buildTimedReadings(List<M> data,
                                                              int readingWindowSeconds,
                                                              int numberOfReadings,
                                                              Function<List<M>, SensorDataDTO> lastReadingMapper,
                                                              IntervalReader<M> intervalReader) {
        if (CollectionUtils.isEmpty(data)) {
            return Try.success(List.of(SensorDataDTO.empty()));
        }

        if (readingWindowSeconds == 0 && numberOfReadings == 0) {
            return Try.success(List.of(lastReadingMapper.apply(data)));
        }

        return Try.success(intervalReader.apply(data, readingWindowSeconds, numberOfReadings));
    }

    protected <M> List<SensorDataDTO> getReadingsByInterval(List<M> data,
                                                            int readingWindowSeconds,
                                                            int numberOfReadings,
                                                            ToLongFunction<M> timestampExtractor,
                                                            Function<M, SensorDataDTO> mapper) {
        int lastIndex = data.size() - 1;
        var windowReadings = new java.util.LinkedList<M>();
        long lastTimeStamp = timestampExtractor.applyAsLong(data.getLast());
        for (int i = lastIndex; i >= 0; i--) {
            long readingTimeStamp = timestampExtractor.applyAsLong(data.get(i));
            long diffSeconds = lastTimeStamp - readingTimeStamp;
            if (diffSeconds <= readingWindowSeconds) {
                windowReadings.addFirst(data.get(i));
            }
        }
        var reduced = reduceWindowReadings(windowReadings, numberOfReadings);
        return reduced.stream().map(mapper).collect(java.util.stream.Collectors.toList());
    }

    protected <M> List<M> reduceWindowReadings(List<M> readings, int numberOfReadings) {
        if (readings == null || readings.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        int size = readings.size();
        if (numberOfReadings >= size) {
            return readings; // nothing to reduce
        }
        if (numberOfReadings < 2) {
            throw new IllegalArgumentException("numberOfReadings must be at least 2 to include first and last");
        }
        var result = new java.util.ArrayList<M>();
        result.add(readings.getFirst()); // always include first
        double step = (double) (size - 1) / (numberOfReadings - 1);
        for (int i = 1; i < numberOfReadings - 1; i++) {
            int index = (int) Math.round(i * step);
            result.add(readings.get(index));
        }
        result.add(readings.get(size - 1)); // always include last
        return result;
    }
}
