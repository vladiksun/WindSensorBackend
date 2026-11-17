package com.vb.wingfoil;

import com.vb.wingfoil.provider.WindDataProvider;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Option;
import io.vavr.control.Try;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public ProxyService(
            List<WindDataProvider<?>> windDataProviders, WindSensorConfig windSensorConfig, ObjectMapper objectMapper) {
        windDataProvidersByName = windDataProviders.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getName() != null && !p.getName().isBlank())
                .collect(Collectors.toMap(
                        WindDataProvider::getName, // key mapper
                        Function.identity(), // value mapper (the provider itself)
                        (existing, replacement) -> existing, // merge strategy on duplicate keys: keep the first
                        HashMap::new // preserves iteration order of the stream
                        ));

        this.windSensorConfig = windSensorConfig;
        this.objectMapper = objectMapper;

        httpClient = buildHttpClient(windSensorConfig);
    }

    /**
     * Every provider call goes through this client, so its call duration has to be bounded: an upstream that
     * accepts the TCP connection and then stalls would otherwise pin a request thread indefinitely, because
     * {@code HttpClients.createDefault()} leaves the wait for the response unbounded (its
     * {@code RequestConfig.responseTimeout} is null) and bounds only connect and pool-lease, with library
     * defaults of three minutes each. The bounds here come from {@code wind-sensor.upstream-*} and default to
     * 10 s to connect, 30 s for the response and 10 s to lease a connection from the pool. Connect timeout
     * lives on the connection config because {@code RequestConfig.setConnectTimeout} is deprecated in
     * HttpClient 5.6.
     */
    static CloseableHttpClient buildHttpClient(WindSensorConfig windSensorConfig) {
        var connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(windSensorConfig.getUpstreamConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .build();

        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        var requestConfig = RequestConfig.custom()
                .setResponseTimeout(windSensorConfig.getUpstreamResponseTimeoutSeconds(), TimeUnit.SECONDS)
                .setConnectionRequestTimeout(
                        windSensorConfig.getUpstreamConnectionRequestTimeoutSeconds(), TimeUnit.SECONDS)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // The default strategy re-executes a request when the upstream answers 503 or 429, which would
                // turn one client request into several upstream calls (and add a second of waiting to every
                // watch poll). One request per client request is the contract here; retries are not a policy
                // this service has chosen.
                .disableAutomaticRetries()
                .build();
    }

    Try<List<SensorDataDTO>> requestTimedReadings(
            Option<Integer> mayBeAreaReadingWindow, Option<Integer> maybeAreaNumberOfReadings, SensorDTO sensor) {
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
        request.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);

        // Providers may require upstream-specific headers (Windguru answers 401 without a Referer; the 1chip
        // page is HTML and asks for HTML back). They override the defaults above rather than being added
        // next to them, so an upstream never receives two values for the same header. Providers that declare
        // none are unaffected because their default map is empty.
        var providerHeaders = provider.requestHeaders(sensorId);
        if (providerHeaders != null) {
            providerHeaders.forEach((headerName, headerValue) -> {
                if (headerName != null && !headerName.isBlank() && headerValue != null) {
                    request.setHeader(headerName, headerValue);
                }
            });
        }

        var context = new ResponseHandlerContext(
                mayBeAreaReadingWindow, maybeAreaNumberOfReadings, sensor, url, provider, sensorId);

        return Try.of(() -> httpClient.execute(request, response -> handleResponse(context, response)))
                .flatMap(o -> o)
                .onFailure(e -> logger.error("Failed to get sensor data", e));
    }

    private Try<List<SensorDataDTO>> handleResponse(ResponseHandlerContext context, ClassicHttpResponse response)
            throws IOException, ParseException {
        int status = response.getCode();
        if (status < HttpStatus.SC_OK || status >= HttpStatus.SC_REDIRECTION) {
            throw new IOException("Upstream call failed with status " + status + " for " + context.url());
        }
        var entity = response.getEntity();

        var body = entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";

        var readingWindow = context.readingWindow().getOrElse(READING_WINDOW_SECONDS_DEFAULT);
        var numberOfReadings = context.numberOfReadings().getOrElse(NUMBER_OF_READINGS_DEFAULT);

        // take priority from the sensor meta if exists
        readingWindow = Option.of(context.sensor().readingWindow()).getOrElse(readingWindow);
        numberOfReadings = Option.of(context.sensor().numberOfReadings()).getOrElse(numberOfReadings);

        return context.provider().extractTimedReadings(context.sensorId(), body, readingWindow, numberOfReadings);
    }

    Try<List<SpotDataDTO>> requestSpotsData(boolean isDebug) {
        String url;
        if (isDebug) {
            url = windSensorConfig.getSpotsTestDataUrl();
        } else {
            url = windSensorConfig.getSpotsDataUrl();
        }

        return requestSpotsDataForURL(url);
    }

    Try<List<SpotDataDTO>> requestSpotsDataForDahab(boolean isDebug) {
        var url = windSensorConfig.getSpotsDahabUrl();

        return requestSpotsDataForURL(url);
    }

    private Try<List<SpotDataDTO>> requestSpotsDataForURL(String url) {
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
        if (response == null || response.isBlank()) {
            return List.of();
        }

        var result = objectMapper.readValue(response, argument);

        return result;
    }
}
