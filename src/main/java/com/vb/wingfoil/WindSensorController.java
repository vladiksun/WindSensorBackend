package com.vb.wingfoil;

import io.micronaut.http.HttpResponse;
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
    public HttpResponse<List<SensorDataDTO>> getSensorData(@Body SensorRequestDTO sensorRequest) {
        var outcome = proxyService
                .requestTimedReadings(
                        Option.of(sensorRequest.readingWindow()),
                        Option.of(sensorRequest.numberOfReadings()),
                        sensorRequest.sensor())
                .get();

        // The body is identical whether a valid cache entry answered or a provider call did; only this header says
        // which, so an operator can tell "the provider is broken" from "we are serving a cached reading". A failure
        // still propagates through Try.get() to GlobalExceptionHandler unchanged.
        return HttpResponse.ok(outcome.readings())
                .header("X-Cache", outcome.status().name());
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
