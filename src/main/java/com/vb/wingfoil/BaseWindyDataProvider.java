package com.vb.wingfoil;

import io.micronaut.serde.ObjectMapper;

public abstract class BaseWindyDataProvider implements WindDataProvider {

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
}
