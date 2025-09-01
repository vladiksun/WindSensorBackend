package com.vb.wingfoil;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

@Controller
public class WindSenorController {

    private final ProxyService proxyService;

    public WindSenorController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Get("/sensor-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public SensorDTO getSensorData(String provider, String sensorId) {
        if (provider == null) throw new IllegalArgumentException("provider parameter must not be null");
        if (sensorId == null || sensorId.isBlank()) throw new IllegalArgumentException("sensorId parameter must not be null or blank");

        return proxyService.requestSensorData(provider, sensorId).get();
    }

}
