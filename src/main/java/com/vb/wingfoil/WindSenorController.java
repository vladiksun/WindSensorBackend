package com.vb.wingfoil;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.hateoas.Link;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.vavr.control.Option;

import java.util.List;

@Controller
public class WindSenorController {

    private final ProxyService proxyService;

    public WindSenorController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Post("/sensor-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SensorDataDTO> getSensorData(@Body SensorRequestDTO sensorRequest) {
        return proxyService.requestTimedReadings(
                Option.of(sensorRequest.readingWindow()),
                Option.of(sensorRequest.numberOfReadings()),
                sensorRequest.sensor()
                ).get();
    }

    @Get("/spots-data")
    @ExecuteOn(TaskExecutors.VIRTUAL)
    public List<SpotDataDTO> getSpotsData(@QueryValue(defaultValue = "false") boolean isDebug) {
        return proxyService.requestSpotsData(isDebug).get();
    }

    @Error(global = true)
    public HttpResponse<JsonError> error(HttpRequest<?> request, Throwable e) {
        var error = new JsonError(e.getMessage())
                .link(Link.SELF, Link.of(request.getUri()));

        return HttpResponse.<JsonError>serverError()
                .body(error);
    }

}
