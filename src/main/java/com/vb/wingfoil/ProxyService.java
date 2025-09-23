package com.vb.wingfoil;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Option;
import io.vavr.control.Try;
import jakarta.inject.Singleton;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class ProxyService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private Argument<List<SpotData>> argument = Argument.listOf(SpotData.class);

    private Map<String, WindDataProvider> windDataProvidersByName;

    private final CloseableHttpClient httpClient;

    private final WindSensorConfig windSensorConfig;

    private final ObjectMapper objectMapper;

    public ProxyService(List<WindDataProvider> windDataProviders,
                        WindSensorConfig windSensorConfig,
                        ObjectMapper objectMapper) {
        windDataProvidersByName = windDataProviders.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getName() != null && !p.getName().isBlank())
                .collect(Collectors.toMap(
                        WindDataProvider::getName,        // key mapper
                        Function.identity(),              // value mapper (the provider itself)
                        (existing, replacement) -> existing, // merge strategy on duplicate keys: keep the first
                        HashMap::new                // preserves iteration order of the stream
                ));

        this.windSensorConfig = windSensorConfig;
        this.objectMapper = objectMapper;

        httpClient = HttpClients.createDefault();
    }

    Try<SensorDataDTO> requestSensorDataLastReading(String providerName, String sensorId) {
        return requestTimedReadings(providerName, sensorId, 0, 0).map(List::getFirst);
    }

    Try<List<SensorDataDTO>> requestTimedReadings(String providerName,
                                                  String sensorId,
                                                  int readingWindowSeconds,
                                                  int numberOfReadings) {
        WindDataProvider provider;
        String effectiveSensorId;

        if (sensorId.startsWith(NeduetDataProvider.NAME)) {
            provider = windDataProvidersByName.get(NeduetDataProvider.NAME);
            effectiveSensorId = sensorId.replace(NeduetDataProvider.NAME + "_", "");
            var test = "";
        } else {
            effectiveSensorId = sensorId;
            provider = Option.of(windDataProvidersByName.get(providerName))
                    .getOrElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerName));
        }

        var url = provider.getCallUrl(effectiveSensorId);

        var request = new HttpGet(url);
        request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);

        return Try.of(() -> httpClient.execute(request, response -> {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream call failed with status " + status + " for " + url);
            }
            var entity = response.getEntity();

            var body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

            return provider.extractTimedReadings(effectiveSensorId, body, readingWindowSeconds, numberOfReadings);
        }))
        .flatMap(o -> o)
        .onFailure(e -> logger.error("Failed to get sensor data", e));
    }

    Try<List<SpotData>> requestSpotsData() {
        var url = windSensorConfig.getSpotsDataUrl();
        var mediaType = windSensorConfig.getSpotsDataMediaType();

        var request = new HttpGet(url);

        request.addHeader(HttpHeaders.ACCEPT, mediaType);

        return Try.of(() -> httpClient.execute(request, response -> {
            int status = response.getCode();
            if (status < HttpStatus.SC_OK || status >= HttpStatus.SC_REDIRECTION) {
                throw new IOException("Upstream call failed with status " + status + " for " + url);
            }
            var entity = response.getEntity();

            var body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

            return parseSpotsDataResponse(body);
        }))
        .onFailure(e -> logger.error("Failed to get spots data", e));
    }

    private List<SpotData> parseSpotsDataResponse(String response) throws IOException {
        if (response == null || response.isBlank()) return List.of();

        var result = objectMapper.readValue(response, argument);

        return result;
    }

}
