package com.vb.wingfoil;

import com.vb.wingfoil.response.windy.WindMeasurement;
import com.vb.wingfoil.response.windy.WindStationApiResponse;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

@Singleton
public class WindyDataProvider extends BaseWindyDataProvider<WindStationApiResponse> {

    public static final String NAME = "windy";
    private static final Logger log = LoggerFactory.getLogger(WindyDataProvider.class);

    protected WindyDataProvider(@Named(NAME) WindDataProviderConfig windDataProviderConfig,
                                ObjectMapper objectMapper) {
        super(windDataProviderConfig, objectMapper);
    }

    @Override
    public String getCallUrl(String sensorId) {
        return getUrl().formatted(sensorId);
    }

    @Override
    public Try<List<SensorDataDTO>> extractTimedReadings(String response, int readingWindowSeconds, int numberOfReadings) throws IOException {
        if (response == null || response.isBlank()) return Try.success(List.of(SensorDataDTO.empty()));

        var windyResponse = objectMapper.readValue(response, WindStationApiResponse.class);

        if (windyResponse == null) return Try.success(List.of(SensorDataDTO.empty()));

        if (!"success".equals(windyResponse.status())) {
            return Try.failure(new RuntimeException("Windy provider returned an error. Full response: %s".formatted(response)));
        }

        var data = windyResponse.response().data();
        if (CollectionUtils.isEmpty(data)) {
            return Try.success(List.of(SensorDataDTO.empty()));
        }

        if (readingWindowSeconds == 0 && numberOfReadings == 0) {
            return Try.success(List.of(getLastReading(data)));
        }

        return Try.success(getReadingByInterval(data, readingWindowSeconds, numberOfReadings));
    }

    private List<SensorDataDTO> getReadingByInterval(List<WindMeasurement> data, int readingWindowSeconds, int numberOfReadings) {
        var lastIndex = data.size() - 1;

        var windowReadings = new LinkedList<WindMeasurement>();

        long lastTimeStamp = data.getLast().timestamp();

        for (int i = lastIndex; i >= 0; i--) {
            var readingTimeStamp = data.get(i).timestamp();
            var diffSeconds = lastTimeStamp - readingTimeStamp;
            if (diffSeconds <= readingWindowSeconds) {
                windowReadings.addFirst(data.get(i));
            }
        }

        var reducedWindowReadings = reduceWindowTimestamps(windowReadings, numberOfReadings);

        return reducedWindowReadings.stream().map(windMeasurement -> {
            var windMax = windMeasurement.windMax();
            var windAvg = windMeasurement.windAvg();
            var windMin = windMeasurement.windMin();
            var windDirection = windMeasurement.windDirection();
            var timestamp = windMeasurement.timestamp();

            return new SensorDataDTO(windMax, windAvg, windMin, windDirection, timestamp);
        }).collect(Collectors.toList());
    }

    /**
     * Reduces the list of timestamps to exactly n elements.
     * Always includes the first and last elements, and distributes
     * the rest as evenly as possible.
     *
     * @param readings input list of timestamps (must be sorted)
     * @param numberOfReadings desired number of output elements
     * @return reduced list of timestamps
     */
    public List<WindMeasurement> reduceWindowTimestamps(List<WindMeasurement> readings, int numberOfReadings) {
        if (readings == null || readings.isEmpty()) {
            return new ArrayList<>();
        }

        int size = readings.size();
        if (numberOfReadings >= size) {
            return readings; // nothing to reduce
        }

        if (numberOfReadings < 2) {
            throw new IllegalArgumentException("numberOfReadings must be at least 2 to include first and last");
        }

        List<WindMeasurement> result = new ArrayList<>();
        result.add(readings.get(0)); // always include first

        double step = (double)(size - 1) / (numberOfReadings - 1);

        for (int i = 1; i < numberOfReadings - 1; i++) {
            int index = (int) Math.round(i * step);
            result.add(readings.get(index));
        }

        result.add(readings.get(size - 1)); // always include last
        return result;
    }

    /**
     * Reduces the list of timestamps to exactly n elements.
     * Always includes the first and last elements, and distributes
     * the rest as evenly as possible.
     *
     * @param timestamps input list of timestamps (must be sorted)
     * @param numberOfReadings desired number of output elements
     * @return reduced list of timestamps
     */
    public List<Long> reduceTimestamps(List<Long> timestamps, int numberOfReadings) {
        if (timestamps == null || timestamps.isEmpty()) {
            return new ArrayList<>();
        }

        int size = timestamps.size();
        if (numberOfReadings >= size) {
            return timestamps; // nothing to reduce
        }

        if (numberOfReadings < 2) {
            throw new IllegalArgumentException("numberOfReadings must be at least 2 to include first and last");
        }

        List<Long> result = new ArrayList<>();
        result.add(timestamps.get(0)); // always include first

        double step = (double)(size - 1) / (numberOfReadings - 1);

        for (int i = 1; i < numberOfReadings - 1; i++) {
            int index = (int) Math.round(i * step);
            result.add(timestamps.get(index));
        }

        result.add(timestamps.get(size - 1)); // always include last
        return result;
    }

    private SensorDataDTO getLastReading(List<WindMeasurement> data) {
        var lastData = data.getLast();

        var windMax = lastData.windMax();
        var windAvg = lastData.windAvg();
        var windMin = lastData.windMin();
        var windDirection = lastData.windDirection();
        var timestamp = lastData.timestamp();

        return new SensorDataDTO(windMax, windAvg, windMin, windDirection, timestamp);
    }
}
