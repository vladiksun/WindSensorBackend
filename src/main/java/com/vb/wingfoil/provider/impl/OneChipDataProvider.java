package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.onechip.OneChipMeasurement;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

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
        var arrivedDate = extractArrivedDate(html);

        Document document = Jsoup.parse(html);
        var rows = document.select("#data_table tr");

        var measurements = new ArrayList<OneChipMeasurement>();
        for (Element row : rows) {
            var cells = row.select("td, th");
            if (cells.size() < MIN_CELLS_FOR_DATA_ROW) {
                continue;
            }
            if ("hh:mm".equalsIgnoreCase(cells.get(CELL_TIME).text().trim())) {
                continue; // header row
            }
            var measurement = parseRow(cells, arrivedDate, this.zone);
            if (measurement != null) {
                measurements.add(measurement);
            }
        }

        // 1chip.ru lists past-data rows newest-first; reverse to ascending (oldest -> newest)
        // because getReadingsByInterval/getLastReading treat the last element as the most recent.
        Collections.reverse(measurements);
        return measurements;
    }

    private OneChipMeasurement parseRow(List<Element> cells, LocalDate date, ZoneId zone) {
        try {
            var time = LocalTime.parse(cells.get(CELL_TIME).text().trim(), ROW_TIME_FORMATTER);
            var min = Float.parseFloat(cells.get(CELL_MIN).text().trim());
            var avg = Float.parseFloat(cells.get(CELL_AVG).text().trim());
            var gust = Float.parseFloat(cells.get(CELL_GUST).text().trim());
            var direction = Float.parseFloat(
                    cells.get(CELL_DIRECTION).text().replace("\u00B0", "").trim());
            var timestamp = LocalDateTime.of(date, time).atZone(zone).toEpochSecond();
            return new OneChipMeasurement(min, avg, gust, direction, timestamp);
        } catch (Exception e) {
            log.debug("Skipping malformed onechip row: {}", cells.toString(), e);
            return null;
        }
    }

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
