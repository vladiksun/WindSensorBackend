package com.vb.wingfoil;

import io.micronaut.json.tree.JsonArray;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

@Singleton
public class WindyDataProvider extends BaseWindyDataProvider {

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
    public SensorDataDTO parseSensorDataResponse(String response) throws IOException {
        if (response == null || response.isBlank()) return SensorDataDTO.empty();

        var node = objectMapper.readValue(response, JsonNode.class);

        var data = node.get("response").get("data");

        if (data == null) return SensorDataDTO.empty();

        if (data instanceof JsonArray dataArr) {
            if (dataArr.size() == 0) return SensorDataDTO.empty();

            var lastData = dataArr.get(dataArr.size() - 1);

            var windMax = lastData.get("wind_max").getFloatValue();
            var windAvg = lastData.get("wind_avg").getFloatValue();
            var windMin = lastData.get("wind_min").getFloatValue();
            var windDirection = lastData.get("wind_direction").getFloatValue();
            var temperature = lastData.get("temperature").getFloatValue();

            return new SensorDataDTO(windMax, windAvg, windMin, windDirection, temperature);
        }

        return SensorDataDTO.empty();
    }
}
