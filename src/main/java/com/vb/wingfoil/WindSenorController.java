package com.vb.wingfoil;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.List;

@Controller
public class WindSenorController {

    private final ProxyService proxyService;

    public WindSenorController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Get("/sensor-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public SensorDataDTO getSensorData(String provider, String sensorId) {
        if (provider == null) throw new IllegalArgumentException("provider parameter must not be null");
        if (sensorId == null || sensorId.isBlank()) throw new IllegalArgumentException("sensorId parameter must not be null or blank");

        return proxyService.requestSensorData(provider, sensorId).get();
    }

    @Get("/spots-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SpotData> getSpotsData() {
        return proxyService.requestSpotsData().get();
    }

}
