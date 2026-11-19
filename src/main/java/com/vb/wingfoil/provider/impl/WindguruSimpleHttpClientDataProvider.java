package com.vb.wingfoil.provider.impl;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

import com.vb.wingfoil.AppException;
import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig;
import com.vb.wingfoil.response.windguru.WindguruMeasurement;
import com.vb.wingfoil.response.windguru.WindguruStationData;
import com.vb.wingfoil.response.windguru.WindguruTokenData;
import com.vb.wingfoil.response.windguru.WindguruTokenResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Option;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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

/**
 * Provider for anemometers published on Windguru station pages
 * ({@code https://www.windguru.cz/station/<stationId>}).
 * <p>
 * The page loads its readings from {@code GET /int/iapi.php?q=station_data}, which answers with a
 * columnar payload: parallel arrays ({@code unixtime}, {@code wind_avg}, {@code wind_max},
 * {@code wind_min}, {@code wind_direction}) of equal length, ascending, speeds published in knots and
 * timestamps already in epoch seconds — so the only conversion needed is knots to metres per second,
 * applied exactly once by {@link #knotsToMetresPerSecond(float)} while the columns are unzipped, and no
 * time-zone arithmetic at all. Windguru cannot be asked for metric speeds instead: {@code unit_speed},
 * {@code units} and {@code speed_unit} were each verified to leave this payload unchanged, so publishing
 * metres per second is this provider's own normalization.
 * <p>
 * Two upstream quirks shape this provider:
 * <ul>
 *   <li>Windguru answers {@code 401}/{@code 403} for its data endpoints unless the request names a windguru.cz page
 *       as its referring page <em>and</em> carries a self-minted {@code X-WG-Token}. {@link #requestHeaders(String)}
 *       supplies both, minting that token lazily through a small client of its own and reusing it while it is
 *       still valid;</li>
 *   <li>a valid range with no readings comes back as a bare JSON array ({@code []}), and published gaps
 *       appear as {@code null} inside the numeric arrays.</li>
 * </ul>
 */
@Singleton
public class WindguruSimpleHttpClientDataProvider extends BaseWindyDataProvider<WindguruMeasurement>
        implements AutoCloseable {

    public static final String NAME = "windguru";

    private static final Logger log = LoggerFactory.getLogger(WindguruSimpleHttpClientDataProvider.class);

    /**
     * How much history to ask for in one call. The provider contract only receives the sensor id, so the
     * requested reading window cannot narrow this range; it is sized generously (24 h) to cover any
     * window a client may ask for, and {@code getReadingsByInterval} then trims it. Windows longer than
     * this simply return fewer readings rather than failing.
     */
    static final long LOOK_BACK_SECONDS = 86_400L;

    /** Windguru station page used as the {@code Referer}; without it the data endpoints answer 401. */
    static final String REFERER_PREFIX = "https://www.windguru.cz/station/";

    /** Second header Windguru requires of every {@code station_data} call, next to the {@code Referer}. */
    static final String TOKEN_HEADER = "X-WG-Token";

    /** The {@code iapi.php} action that hands out a short-lived access token. */
    static final String TOKEN_REFRESH_ACTION = "token_refresh";

    /** A minted token is dropped this many seconds before the expiry Windguru advertised for it. */
    static final int TOKEN_TTL_SAFETY_MARGIN_SECONDS = 60;

    /** Longest a minted token is reused, whatever the upstream advertises: half of the observed 1800 s TTL. */
    static final int TOKEN_MAX_LIFETIME_SECONDS = 900;

    /** Shortest lifetime, so an absurdly small advertised TTL cannot produce an already-expired snapshot. */
    static final int TOKEN_MIN_LIFETIME_SECONDS = 1;

    /** Client-safe text reported when no usable token could be obtained; the real cause stays in the logs. */
    private static final String AUTHENTICATION_UNAVAILABLE_MESSAGE = "Windguru authentication unavailable";

    /** One knot in metres per second: the exact rational 1852/3600, i.e. 0.514444… m/s. */
    private static final double METRES_PER_SECOND_PER_KNOT = 1_852d / 3_600d;

    /** Token minted for this process only; {@code null} until the first station-data call needs one. */
    private final AtomicReference<TokenSnapshot> cachedToken = new AtomicReference<>();

    /** Guards refreshing so concurrent station-data requests mint at most one replacement token. */
    private final ReentrantLock tokenLock = new ReentrantLock();

    private final String tokenUrl;

    private final Clock clock;

    private final CloseableHttpClient tokenHttpClient;

    @Inject
    public WindguruSimpleHttpClientDataProvider(
            @Named(NAME) WindDataProviderConfig windDataProviderConfig,
            ObjectMapper objectMapper,
            WindSensorConfig windSensorConfig) {
        this(
                windDataProviderConfig,
                objectMapper,
                tokenUrlFrom(windDataProviderConfig.getUrl()),
                Clock.systemUTC(),
                buildTokenClient(windSensorConfig));
    }

    /**
     * The seam unit tests use: the token endpoint, the clock and the token client are supplied instead of derived
     * from production configuration, so token behaviour is testable without a live Windguru call.
     */
    WindguruSimpleHttpClientDataProvider(
            WindDataProviderConfig windDataProviderConfig,
            ObjectMapper objectMapper,
            String tokenUrl,
            Clock clock,
            CloseableHttpClient tokenHttpClient) {
        super(windDataProviderConfig, objectMapper);
        this.tokenUrl = tokenUrl;
        this.clock = clock;
        this.tokenHttpClient = tokenHttpClient;
    }

    /**
     * The configured template carries the station-id placeholder and the static query parameters
     * ({@code q}, {@code avg_minutes}, {@code graph_info}); only the UTC time range is computed here.
     */
    @Override
    public String getCallUrl(String sensorId) {
        var to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var from = to.minusSeconds(LOOK_BACK_SECONDS);

        return getUrl().formatted(sensorId)
                + "&from=" + DateTimeFormatter.ISO_INSTANT.format(from)
                + "&to=" + DateTimeFormatter.ISO_INSTANT.format(to);
    }

    /**
     * Both headers Windguru requires of a {@code station_data} call. The token comes from the cache when it is still
     * valid and is minted once when it is not; failing to obtain one aborts the request here instead of spending an
     * upstream call that the live evidence shows would be answered {@code 403}.
     */
    @Override
    public Map<String, String> requestHeaders(String sensorId) {
        return Map.of("Referer", REFERER_PREFIX + sensorId, TOKEN_HEADER, currentValidToken());
    }

    @Override
    public Try<List<SensorDataDTO>> extractTimedReadings(
            String sensorId, String response, int readingWindowSeconds, int numberOfReadings) {
        if (response == null || response.isBlank() || isNoDataPayload(response)) {
            log.debug("Windguru station {} returned no station data", sensorId);
            return Try.success(List.of(SensorDataDTO.empty()));
        }

        return Try.of(() -> objectMapper.readValue(response, WindguruStationData.class))
                .map(stationData -> buildTimedReadings(
                        toMeasurements(stationData),
                        readingWindowSeconds,
                        numberOfReadings,
                        this::getLastReading,
                        (d, rw, nr) ->
                                getReadingsByInterval(d, rw, nr, WindguruMeasurement::timestamp, this::mapToDTO)))
                .recover(throwable -> {
                    log.error("Error deserializing windguru station data for {}: {}", sensorId, response, throwable);
                    return Try.success(List.of(SensorDataDTO.empty()));
                })
                .flatMap(o -> o);
    }

    @Override
    public SensorDataDTO mapToDTO(WindguruMeasurement measurement) {
        return new SensorDataDTO(
                measurement.gust(), measurement.avg(), measurement.min(), measurement.dir(), measurement.timestamp());
    }

    /** Published arrays are ascending, but the newest element is picked explicitly to stay order-agnostic. */
    @Override
    public SensorDataDTO getLastReading(List<WindguruMeasurement> data) {
        return data.stream()
                .max(Comparator.comparingLong(WindguruMeasurement::timestamp))
                .map(this::mapToDTO)
                .orElseGet(SensorDataDTO::empty);
    }

    /** Windguru answers a bare {@code []} (not the usual object) when the requested range has no readings. */
    private static boolean isNoDataPayload(String response) {
        for (int i = 0; i < response.length(); i++) {
            var c = response.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '[';
        }
        return true;
    }

    /**
     * Converts one published wind speed from Windguru's knots into the metres per second this backend
     * reports, using exactly one knot = 1852/3600 m/s and rounding half-up to two decimals — the
     * resolution the station itself publishes (whole and one-decimal knots), so 24.3 kn is reported as
     * 12.50 m/s rather than as a float tail that would chase upstream noise.
     * <p>
     * Only wind speeds pass through this method: {@code wind_direction} (degrees) and {@code unixtime}
     * (epoch seconds) are already in the units this backend reports. This is the single conversion site
     * for Windguru values — neither {@link #mapToDTO(WindguruMeasurement)} nor the shared window reducer
     * downstream of it may convert again.
     */
    static float knotsToMetresPerSecond(float knots) {
        return (float) (Math.round(knots * METRES_PER_SECOND_PER_KNOT * 100d) / 100d);
    }

    /**
     * Zips the parallel columns into readings, dropping any index whose timestamp or wind value was
     * published as {@code null} (Windguru's way of marking a gap) or is missing because a column is
     * shorter than {@code unixtime}, and converting the three published speed columns on the way in.
     */
    private static List<WindguruMeasurement> toMeasurements(WindguruStationData stationData) {
        var timestamps = stationData.unixtime();
        if (timestamps == null || timestamps.isEmpty()) {
            return List.of();
        }

        var measurements = new ArrayList<WindguruMeasurement>(timestamps.size());
        for (int i = 0; i < timestamps.size(); i++) {
            var timestamp = valueAt(timestamps, i);
            var avg = valueAt(stationData.windAvg(), i);
            var gust = valueAt(stationData.windMax(), i);
            var min = valueAt(stationData.windMin(), i);
            var direction = valueAt(stationData.windDirection(), i);

            if (timestamp == null || avg == null || gust == null || min == null || direction == null) {
                continue;
            }

            measurements.add(new WindguruMeasurement(
                    knotsToMetresPerSecond(min),
                    knotsToMetresPerSecond(avg),
                    knotsToMetresPerSecond(gust),
                    direction,
                    timestamp));
        }
        return measurements;
    }

    private static <V> V valueAt(List<V> values, int index) {
        return (values == null || index >= values.size()) ? null : values.get(index);
    }

    /** A minted token together with the instant this backend stops trusting it. */
    private record TokenSnapshot(String token, Instant expiresAt) {}

    /** What the token endpoint answered — enough to decide usability without putting the body in a log. */
    private record TokenPayload(int status, String body) {}

    /**
     * The currently usable token: read without locking when the cached snapshot is still valid, and refreshed under
     * the lock with a second check so concurrent requests mint one replacement token rather than one each. A failure
     * is reported as an {@link AppException} with {@code 502}, keeping the transport or payload detail in the cause.
     */
    private String currentValidToken() {
        var cached = cachedToken.get();
        if (isUsable(cached)) {
            return cached.token();
        }

        tokenLock.lock();
        try {
            cached = cachedToken.get();
            if (isUsable(cached)) {
                return cached.token();
            }

            return fetchFreshToken()
                    .map(snapshot -> {
                        cachedToken.set(snapshot);
                        log.debug("Minted a windguru access token valid until {}", snapshot.expiresAt());
                        return snapshot.token();
                    })
                    .getOrElseThrow(cause ->
                            new AppException(HttpStatus.SC_BAD_GATEWAY, AUTHENTICATION_UNAVAILABLE_MESSAGE, cause));
        } finally {
            tokenLock.unlock();
        }
    }

    /** Strictly-before-expiry, so a token is never sent in the last second of its borrowed lifetime. */
    private boolean isUsable(TokenSnapshot snapshot) {
        return snapshot != null && snapshot.expiresAt().isAfter(clock.instant());
    }

    private Try<TokenSnapshot> fetchFreshToken() {
        return Try.of(this::requestToken)
                .flatMap(this::usableTokenOf)
                .map(data -> new TokenSnapshot(
                        data.token(), clock.instant().plusSeconds(effectiveLifetimeSeconds(data.ttl()))))
                .onFailure(cause -> log.error("Could not obtain a windguru access token from {}", tokenUrl, cause));
    }

    /** One GET against the token action: JSON-only {@code Accept}, no cookies, no redirects, no retries. */
    private TokenPayload requestToken() throws IOException, ParseException {
        var request = new HttpGet(tokenUrl);
        request.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);

        return tokenHttpClient.execute(request, response -> new TokenPayload(response.getCode(), bodyOf(response)));
    }

    /** Only HTTP 2xx plus a non-blank {@code data.token} counts as a token this provider may use. */
    private Try<WindguruTokenData> usableTokenOf(TokenPayload payload) {
        if (payload.status() < HttpStatus.SC_OK || payload.status() >= HttpStatus.SC_MULTIPLE_CHOICES) {
            return Try.failure(new IOException(
                    "Windguru token endpoint answered HTTP %d for %s".formatted(payload.status(), tokenUrl)));
        }

        return Try.of(() -> objectMapper.readValue(payload.body(), WindguruTokenResponse.class))
                .flatMap(response -> Option.of(response.data())
                        .filter(data -> data.token() != null && !data.token().isBlank())
                        .toTry(() -> new IOException("Windguru token payload carried no usable token")));
    }

    /**
     * How long a minted token is trusted: the advertised TTL minus a safety margin, capped so an upstream that
     * advertises half an hour is refreshed well before it expires, and floored at one second. A missing or
     * non-positive TTL falls back to the cap rather than to an unbounded lifetime.
     */
    static int effectiveLifetimeSeconds(Integer advertisedTtlSeconds) {
        if (advertisedTtlSeconds == null || advertisedTtlSeconds <= 0) {
            return TOKEN_MAX_LIFETIME_SECONDS;
        }

        var safeLifetime = advertisedTtlSeconds - TOKEN_TTL_SAFETY_MARGIN_SECONDS;

        return Math.max(Math.min(safeLifetime, TOKEN_MAX_LIFETIME_SECONDS), TOKEN_MIN_LIFETIME_SECONDS);
    }

    /**
     * The token action lives on the same script as the configured station-data action, so its URL is derived from
     * that template rather than configured separately: keep everything before the first {@code ?}, drop any
     * placeholder left in the path, and ask for exactly {@code q=token_refresh}.
     */
    static String tokenUrlFrom(String stationDataUrlTemplate) {
        var queryStart = stationDataUrlTemplate.indexOf('?');
        var path = queryStart < 0 ? stationDataUrlTemplate : stationDataUrlTemplate.substring(0, queryStart);

        return path.replace("%s", "") + "?q=" + TOKEN_REFRESH_ACTION;
    }

    /** The same timeout bounds as the shared upstream client, on a client that owns no session state. */
    static CloseableHttpClient buildTokenClient(WindSensorConfig windSensorConfig) {
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
                // One call per expiry, exactly: no re-execution behind a 429/503, no cookie Windguru could plant,
                // no redirect chain followed.
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableRedirectHandling()
                .build();
    }

    private static String bodyOf(ClassicHttpResponse response) throws IOException, ParseException {
        var entity = response.getEntity();

        return entity != null ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : "";
    }

    /** The token client belongs to this provider, so destroying the bean closes it. */
    @Override
    public void close() throws IOException {
        tokenHttpClient.close();
    }
}
