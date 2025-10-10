package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.neduet.NeduetMeasurement;
import com.vb.wingfoil.response.neduet.NeduetStationInfo;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

@Singleton
public class NeduetDataProvider extends BaseWindyDataProvider<NeduetMeasurement> {

    public static final String NAME = "neduet";

    private static final Logger log = LoggerFactory.getLogger(NeduetDataProvider.class);

    private final Argument<List<NeduetStationInfo>> argument = Argument.listOf(NeduetStationInfo.class);

    protected NeduetDataProvider(@Named(NAME) WindDataProviderConfig windDataProviderConfig,
                                 ObjectMapper objectMapper) {
        super(windDataProviderConfig, objectMapper);
    }

    @Override
    public String getCallUrl(String sensorId) {
        return getUrl();
    }

    @Override
    public Try<List<SensorDataDTO>> extractTimedReadings(String sensorId,
                                                         String response,
                                                         int readingWindowSeconds,
                                                         int numberOfReadings) throws IOException {
        if (response == null || response.isBlank()) return Try.success(List.of(SensorDataDTO.empty()));

        List<NeduetStationInfo> stations = objectMapper.readValue(response, argument);

        var data = stations.stream()
                .filter(neduetStationInfo -> sensorId.equals(neduetStationInfo.id()))
                .findFirst()
                .map(NeduetStationInfo::data).orElse(List.of());

        return buildTimedReadings(
                data,
                readingWindowSeconds,
                numberOfReadings,
                this::getLastReading,
                (d, rw, nr) -> getReadingsByInterval(
                        d,
                        rw,
                        nr,
                        NeduetMeasurement::timestamp,
                        this::mapToDTO
                )
        );
    }

    @Override
    public SensorDataDTO mapToDTO(NeduetMeasurement measurement) {
        return new SensorDataDTO(
                measurement.max(),
                measurement.avr(),
                measurement.min(),
                measurement.dir(),
                measurement.timestamp()
        );
    }

    @Override
    public SensorDataDTO getLastReading(List<NeduetMeasurement> data) {
        var lastData = data.getLast();

        var windMax = lastData.max();
        var windAvg = lastData.avr();
        var windMin = lastData.min();
        var windDirection = lastData.dir();
        var timestamp = lastData.timestamp();

        return new SensorDataDTO(windMax, windAvg, windMin, windDirection, timestamp);
    }
}
