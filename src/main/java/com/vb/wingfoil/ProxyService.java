package com.vb.wingfoil;

import com.vb.wingfoil.provider.WindDataProvider;
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
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
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

    public static final int READING_WINDOW_SECONDS_DEFAULT = 3600;

    public static final int NUMBER_OF_READINGS_DEFAULT = 5;

    private final Argument<List<SpotDataDTO>> argument = Argument.listOf(SpotDataDTO.class);

    private final Map<String, WindDataProvider<?>> windDataProvidersByName;

    private final CloseableHttpClient httpClient;

    private final WindSensorConfig windSensorConfig;

    private final ObjectMapper objectMapper;

    public ProxyService(List<WindDataProvider<?>> windDataProviders,
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

    Try<List<SensorDataDTO>> requestTimedReadings(Option<Integer> mayBeAreaReadingWindow,
                                                  Option<Integer> maybeAreaNumberOfReadings,
                                                  SensorDTO sensor) {
        var providerCode = sensor.provider();
        var sensorId = sensor.id();
        if (providerCode == null) {
            return Try.failure(new IllegalArgumentException("provider parameter must not be null"));
        }
        if (sensorId == null || sensorId.isBlank()) {
            return Try.failure(new IllegalArgumentException("sensorId parameter must not be null or blank"));
        }

        var provider = Option.of(windDataProvidersByName.get(providerCode))
                .getOrElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerCode));

        var url = provider.getCallUrl(sensorId);

        var request = new HttpGet(url);
        request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);

        return Try.of(() -> httpClient.execute(request, response -> handleResponse(
                        mayBeAreaReadingWindow,
                        maybeAreaNumberOfReadings,
                        sensor,
                        response,
                        url,
                        provider,
                        sensorId)))
        .flatMap(o -> o)
        .onFailure(e -> logger.error("Failed to get sensor data", e));
    }

    private Try<List<SensorDataDTO>> handleResponse(Option<Integer> mayBeAreaReadingWindow,
                                                    Option<Integer> maybeAreaNumberOfReadings,
                                                    SensorDTO sensor,
                                                    ClassicHttpResponse response,
                                                    String url,
                                                    WindDataProvider<?> provider,
                                                    String sensorId) throws IOException, ParseException {
        int status = response.getCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Upstream call failed with status " + status + " for " + url);
        }
        var entity = response.getEntity();

        var body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

        var readingWindow = mayBeAreaReadingWindow.getOrElse(READING_WINDOW_SECONDS_DEFAULT);
        var numberOfReadings =  maybeAreaNumberOfReadings.getOrElse(NUMBER_OF_READINGS_DEFAULT);

        // take priority from the sensor meta if exists
        readingWindow = Option.of(sensor.readingWindow()).getOrElse(readingWindow);
        numberOfReadings = Option.of(sensor.numberOfReadings()).getOrElse(numberOfReadings);

        return provider.extractTimedReadings(sensorId, body, readingWindow, numberOfReadings);
    }

    Try<List<SpotDataDTO>> requestSpotsData(boolean isDebug) {
        String url;
        if (isDebug) {
            url = "https://raw.githubusercontent.com/vladiksun/WindSensorConfig/refs/heads/main/spots_test.json";
        } else {
            url = windSensorConfig.getSpotsDataUrl();
        }

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

    private List<SpotDataDTO> parseSpotsDataResponse(String response) throws IOException {
        if (response == null || response.isBlank()) return List.of();

        var result = objectMapper.readValue(response, argument);

        return result;
    }
}
