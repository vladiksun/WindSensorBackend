package com.vb.wingfoil;

import com.vb.wingfoil.response.windy.WindStationApiResponse;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

@Singleton
public class WindyDataProvider extends BaseWindyDataProvider<WindStationApiResponse> {

    public static final String NAME = "windy";

    protected WindyDataProvider(@Named(NAME) WindDataProviderConfig windDataProviderConfig,
                                ObjectMapper objectMapper) {
        super(windDataProviderConfig, objectMapper);
    }

    @Override
    public String getCallUrl(String sensorId) {
        return getUrl().formatted(sensorId);
    }

    @Override
    public Try<SensorDataDTO> extractLastReading(String response) throws IOException {
        if (response == null || response.isBlank()) return Try.success(SensorDataDTO.empty());

        var windyResponse = objectMapper.readValue(response, WindStationApiResponse.class);

        if (windyResponse == null) return Try.success(SensorDataDTO.empty());

        if (!"success".equals(windyResponse.status())) {
            return Try.failure(new RuntimeException("Windy provider returned an error. Full response: %s".formatted(response)));
        }

        var data = windyResponse.response().data();
        if (CollectionUtils.isEmpty(data)) {
            return Try.success(SensorDataDTO.empty());
        }

        var lastData = data.getLast();

        var windMax = lastData.windMax();
        var windAvg = lastData.windAvg();
        var windMin = lastData.windMin();
        var windDirection = lastData.windDirection();
        var timestamp = lastData.timestamp();

        return Try.success(new SensorDataDTO(windMax, windAvg, windMin, windDirection, timestamp));
    }
}
