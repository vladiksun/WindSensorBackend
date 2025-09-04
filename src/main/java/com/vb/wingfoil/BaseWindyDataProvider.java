package com.vb.wingfoil;

import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.util.List;

public abstract class BaseWindyDataProvider implements WindDataProvider {

    private Argument<List<SpotData>> argument = Argument.listOf(SpotData.class);

    private final String name;

    private final String url;

    protected final ObjectMapper objectMapper;

    protected BaseWindyDataProvider(WindDataProviderConfig windDataProviderConfig, ObjectMapper objectMapper) {
        this.url = windDataProviderConfig.getUrl();
        this.name = windDataProviderConfig.getName();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SpotData> parseSpotsDataResponse(String response) throws IOException {
        if (response == null || response.isBlank()) return List.of();

        var result = objectMapper.readValue(response, argument);

        return result;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUrl() {
        return url;
    }
}
