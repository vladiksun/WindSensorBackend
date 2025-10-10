package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.windy.WindyMeasurement;
import com.vb.wingfoil.response.windy.WindyStationApiResponse;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

@Singleton
public class WindyDataProvider extends BaseWindyDataProvider<WindyMeasurement> {

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
    public Try<List<SensorDataDTO>> extractTimedReadings(String sensorId,
                                                         String response,
                                                         int readingWindowSeconds,
                                                         int numberOfReadings) {
        if (response == null || response.isBlank()) return Try.success(List.of(SensorDataDTO.empty()));

        return Try.of(() -> objectMapper.readValue(response, WindyStationApiResponse.class))
                .map(windyResponse -> {
                    if (!"success".equals(windyResponse.status())) {
                        return Try.<List<SensorDataDTO>>failure(new RuntimeException("Windy provider returned an error. Full response: %s".formatted(response)));
                    }

                    var data = windyResponse.response().data();
                    return buildTimedReadings(
                            data,
                            readingWindowSeconds,
                            numberOfReadings,
                            this::getLastReading,
                            (d, rw, nr) -> getReadingsByInterval(
                                    d,
                                    rw,
                                    nr,
                                    WindyMeasurement::timestamp,
                                    this::mapToDTO
                            )
                    );
        })
        .recover(throwable -> {
                log.error("Error deserializing Windy provider response: {}", response, throwable);
                return Try.success(List.of(SensorDataDTO.empty()));
        }).flatMap(o -> o);
    }

    @Override
    public SensorDataDTO mapToDTO(WindyMeasurement measurement) {
        return new SensorDataDTO(
                measurement.windMax(),
                measurement.windAvg(),
                measurement.windMin(),
                measurement.windDirection(),
                measurement.timestamp()
        );
    }

    @Override
    public SensorDataDTO getLastReading(List<WindyMeasurement> data) {
        var lastData = data.getLast();

        var windMax = lastData.windMax();
        var windAvg = lastData.windAvg();
        var windMin = lastData.windMin();
        var windDirection = lastData.windDirection();
        var timestamp = lastData.timestamp();

        return new SensorDataDTO(windMax, windAvg, windMin, windDirection, timestamp);
    }
}
