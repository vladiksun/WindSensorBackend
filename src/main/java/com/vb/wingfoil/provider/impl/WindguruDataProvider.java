package com.vb.wingfoil.provider.impl;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.windguru.WindguruMeasurement;
import com.vb.wingfoil.response.windguru.WindguruStationData;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 *   <li>Windguru answers {@code 401} for its data endpoints unless the request names a windguru.cz page
 *       as its referring page, which {@link #requestHeaders(String)} supplies;</li>
 *   <li>a valid range with no readings comes back as a bare JSON array ({@code []}), and published gaps
 *       appear as {@code null} inside the numeric arrays.</li>
 * </ul>
 */
@Singleton
public class WindguruDataProvider extends BaseWindyDataProvider<WindguruMeasurement> {

    public static final String NAME = "windguru";

    private static final Logger log = LoggerFactory.getLogger(WindguruDataProvider.class);

    /**
     * How much history to ask for in one call. The provider contract only receives the sensor id, so the
     * requested reading window cannot narrow this range; it is sized generously (24 h) to cover any
     * window a client may ask for, and {@code getReadingsByInterval} then trims it. Windows longer than
     * this simply return fewer readings rather than failing.
     */
    static final long LOOK_BACK_SECONDS = 86_400L;

    /** Windguru station page used as the {@code Referer}; without it the data endpoints answer 401. */
    static final String REFERER_PREFIX = "https://www.windguru.cz/station/";

    /** One knot in metres per second: the exact rational 1852/3600, i.e. 0.514444… m/s. */
    private static final double METRES_PER_SECOND_PER_KNOT = 1_852d / 3_600d;

    protected WindguruDataProvider(
            @Named(NAME) WindDataProviderConfig windDataProviderConfig, ObjectMapper objectMapper) {
        super(windDataProviderConfig, objectMapper);
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

    @Override
    public Map<String, String> requestHeaders(String sensorId) {
        return Map.of("Referer", REFERER_PREFIX + sensorId);
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
}
