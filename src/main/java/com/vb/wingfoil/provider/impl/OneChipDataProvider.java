package com.vb.wingfoil.provider.impl;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.onechip.OneChipMeasurement;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider for sensors that publish data only through the server-side-rendered HTML page at
 * {@code https://1chip.ru/windt.php?id=<sensorId>}. The page has no REST API, so this provider
 * fetches the HTML and parses the past-data table into normalized readings.
 */
@Singleton
public class OneChipDataProvider extends BaseWindyDataProvider<OneChipMeasurement> {

    public static final String NAME = "onechip";

    private static final Logger log = LoggerFactory.getLogger(OneChipDataProvider.class);

    private static final DateTimeFormatter ARRIVED_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter ROW_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern ARRIVED_DATE_PATTERN = Pattern.compile("Arrived[^\\d]*(\\d{2}/\\d{2}/\\d{4})");

    /** Fixed cell indices inside each past-data row of the {@code #data_table} table. */
    private static final int CELL_TIME = 0;

    private static final int CELL_MIN = 2;
    private static final int CELL_AVG = 3;
    private static final int CELL_GUST = 4;
    private static final int CELL_DIRECTION = 7;
    private static final int MIN_CELLS_FOR_DATA_ROW = 8;

    /** Readings up to this many seconds in the future are treated as clock skew, not misdated data. */
    private static final long FUTURE_TOLERANCE_SECONDS = 3600L;

    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * Time zone in which the 1chip.ru page prints its wall-clock times (the sensor's local zone).
     * Resolved from the {@code wind-sensor.wind-providers.onechip.timezone} config; falls back to the
     * system-default zone when unset. Using an explicit zone keeps absolute timestamps stable even when
     * the host/container runs in a different zone (e.g. UTC in Docker).
     */
    private final ZoneId zone;

    protected OneChipDataProvider(
            @Named(NAME) WindDataProviderConfig windDataProviderConfig, ObjectMapper objectMapper) {
        super(windDataProviderConfig, objectMapper);
        this.zone = resolveZone(windDataProviderConfig.getTimezone());
    }

    private static ZoneId resolveZone(String zoneId) {
        return (zoneId == null || zoneId.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zoneId);
    }

    @Override
    public String getCallUrl(String sensorId) {
        return getUrl().formatted(sensorId);
    }

    @Override
    public Try<List<SensorDataDTO>> extractTimedReadings(
            String sensorId, String response, int readingWindowSeconds, int numberOfReadings) {
        if (response == null || response.isBlank()) {
            return Try.success(List.of(SensorDataDTO.empty()));
        }

        return Try.of(() -> parseMeasurements(response))
                .map(measurements -> buildTimedReadings(
                        measurements,
                        readingWindowSeconds,
                        numberOfReadings,
                        this::getLastReading,
                        (d, rw, nr) -> getReadingsByInterval(d, rw, nr, OneChipMeasurement::timestamp, this::mapToDTO)))
                .recover(throwable -> {
                    log.error("Error parsing onechip provider response", throwable);
                    return Try.success(List.of(SensorDataDTO.empty()));
                })
                .flatMap(o -> o);
    }

    @Override
    public SensorDataDTO mapToDTO(OneChipMeasurement m) {
        return new SensorDataDTO(m.gust(), m.avg(), m.min(), m.dir(), m.timestamp());
    }

    @Override
    public SensorDataDTO getLastReading(List<OneChipMeasurement> data) {
        return mapToDTO(data.getLast());
    }

    private List<OneChipMeasurement> parseMeasurements(String html) {
        var anchorDate = extractArrivedDate(html);

        Document document = Jsoup.parse(html);
        var rows = document.select("#data_table tr");

        var measurements = new ArrayList<OneChipMeasurement>();
        LocalDateTime previous = null; // rows are newest-first; previous holds the more-recent row
        for (Element row : rows) {
            var cells = row.select("td, th");
            if (cells.size() < MIN_CELLS_FOR_DATA_ROW) {
                continue;
            }
            if ("hh:mm".equalsIgnoreCase(cells.get(CELL_TIME).text().trim())) {
                continue; // header row
            }
            var values = parseRowValues(cells);
            if (values == null) {
                continue;
            }
            var date = resolveRowDate(values.time(), anchorDate, previous);
            var rowDateTime = LocalDateTime.of(date, values.time());
            previous = rowDateTime;
            var timestamp = rowDateTime.atZone(this.zone).toEpochSecond();
            measurements.add(
                    new OneChipMeasurement(values.min(), values.avg(), values.gust(), values.direction(), timestamp));
        }

        applyFutureGuard(measurements);

        // 1chip.ru lists past-data rows newest-first; reverse to ascending (oldest -> newest)
        // because getReadingsByInterval/getLastReading treat the last element as the most recent.
        Collections.reverse(measurements);
        return measurements;
    }

    /**
     * Resolves the calendar date for a row given the anchor date and the previously-seen (more recent)
     * row. Rows are processed newest-first, so within a single day the wall-clock times strictly decrease;
     * when the time increases we have crossed midnight going backwards and roll the date back until the
     * candidate is strictly before the previous row.
     */
    private static LocalDate resolveRowDate(LocalTime time, LocalDate anchorDate, LocalDateTime previous) {
        if (previous == null) {
            return anchorDate;
        }
        var date = previous.toLocalDate();
        while (!LocalDateTime.of(date, time).isBefore(previous)) {
            date = date.minusDays(1);
        }
        return date;
    }

    /**
     * A reading cannot be from the future. When the reconstructed newest timestamp lands after "now" by
     * more than {@link #FUTURE_TOLERANCE_SECONDS}, the whole series is shifted back by whole days so the
     * newest reading is at or before now, preserving relative spacing between readings.
     */
    private void applyFutureGuard(List<OneChipMeasurement> measurements) {
        if (measurements.isEmpty()) {
            return;
        }
        long maxTs = measurements.stream()
                .mapToLong(OneChipMeasurement::timestamp)
                .max()
                .orElse(0L);
        long overshoot = maxTs - currentTimeSeconds();
        if (overshoot <= FUTURE_TOLERANCE_SECONDS) {
            return;
        }
        long shift = Math.max(1L, overshoot / SECONDS_PER_DAY) * SECONDS_PER_DAY;
        for (int i = 0; i < measurements.size(); i++) {
            var m = measurements.get(i);
            measurements.set(i, new OneChipMeasurement(m.min(), m.avg(), m.gust(), m.dir(), m.timestamp() - shift));
        }
    }

    /** Package-private seam over "current time" so the future-guard is deterministic in unit tests. */
    long currentTimeSeconds() {
        return Instant.now().getEpochSecond();
    }

    private RowValues parseRowValues(List<Element> cells) {
        try {
            var time = LocalTime.parse(cells.get(CELL_TIME).text().trim(), ROW_TIME_FORMATTER);
            var min = Float.parseFloat(cells.get(CELL_MIN).text().trim());
            var avg = Float.parseFloat(cells.get(CELL_AVG).text().trim());
            var gust = Float.parseFloat(cells.get(CELL_GUST).text().trim());
            var direction = Float.parseFloat(
                    cells.get(CELL_DIRECTION).text().replace("\u00B0", "").trim());
            return new RowValues(time, min, avg, gust, direction);
        } catch (Exception e) {
            log.debug("Skipping malformed onechip row: {}", cells.toString(), e);
            return null;
        }
    }

    /** Parsed numeric/time values from a single past-data row, before its calendar date is resolved. */
    private record RowValues(LocalTime time, float min, float avg, float gust, float direction) {}

    private LocalDate extractArrivedDate(String html) {
        Matcher matcher = ARRIVED_DATE_PATTERN.matcher(html);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), ARRIVED_DATE_FORMATTER);
            } catch (Exception e) {
                log.warn("Could not parse onechip 'Arrived' date '{}'; falling back to today", matcher.group(1), e);
            }
        }
        return LocalDate.now();
    }
}
