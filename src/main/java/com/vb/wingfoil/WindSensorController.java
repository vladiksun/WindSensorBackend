package com.vb.wingfoil;

import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.vavr.control.Option;

import java.util.List;

@Controller
public class WindSensorController {

    private final ProxyService proxyService;

    public WindSensorController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Post("/sensor-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SensorDataDTO> getSensorData(@Body SensorRequestDTO sensorRequest) {
        return proxyService
                .requestTimedReadings(
                        Option.of(sensorRequest.readingWindow()),
                        Option.of(sensorRequest.numberOfReadings()),
                        sensorRequest.sensor())
                .get();
    }

    @Get("/spots-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SpotDataDTO> getSpotsData(@QueryValue(defaultValue = "false") boolean isDebug) {
        return proxyService.requestSpotsData(isDebug).get();
    }

    @Get("/spots-data-dahab")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SpotDataDTO> getSpotsDataDahab(@QueryValue(defaultValue = "false") boolean isDebug) {
        return proxyService.requestSpotsDataForDahab(isDebug).get();
    }
}
