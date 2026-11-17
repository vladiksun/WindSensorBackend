package com.vb.wingfoil.provider.impl;

import static com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.response.onechip.OneChipMeasurement;
import io.micronaut.http.HttpHeaders;
import io.micronaut.serde.ObjectMapper;
import io.vavr.control.Option;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider for sensors that publish data only through the server-side-rendered HTML page at
 * {@code https://1chip.ru/windt.php?id=<sensorId>}. The page has no REST API, so this provider fetches
 * the HTML and normalizes its past-data tables into timed readings.
 *
 * <p>The page prints two history tables: a recent one ({@code div#data_table}, roughly three hours of
 * three-minute rows) and a grouped one ({@code div#group_table}, fifteen-minute groups). Each table's
 * header row names its columns — {@code hh:mm}, {@code min}, {@code Avg, m/s}, {@code Gust, m/s} and
 * {@code Dir, °}, the last two spanning two columns each — while data rows carry nine or ten cells
 * depending on position. Columns are therefore located from the header captions instead of fixed cell
 * indices, and a table whose header cannot be resolved is reported rather than guessed at.
 */
@Singleton
public class OneChipDataProvider extends BaseWindyDataProvider<OneChipMeasurement> {

    public static final String NAME = "onechip";

    /**
     * 1chip.ru serves a page, not an API, so the call has to identify an HTML page consumer. One request
     * per client request — no crawling, no retries — which is what makes presenting this identity fair.
     */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    public static final String ACCEPT_HTML = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8";

    public static final String ACCEPT_LANGUAGE = "en";

    private static final Logger log = LoggerFactory.getLogger(OneChipDataProvider.class);

    private static final DateTimeFormatter ARRIVED_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter ROW_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern ARRIVED_DATE_PATTERN = Pattern.compile("Arrived[^\\d]*(\\d{2}/\\d{2}/\\d{4})");

    /** Page containers of the recent (three-minute) and grouped (fifteen-minute) history tables. */
    private static final String RECENT_TABLE_DIV = "data_table";

    private static final String GROUPED_TABLE_DIV = "group_table";

    private static final char DEGREE_SIGN = '\u00B0';

    /** Readings up to this many seconds in the future are clock skew, not misdated data. */
    private static final long FUTURE_TOLERANCE_SECONDS = 3600L;

    /** Below this an overshoot is skew worth clamping; at or above it the date anchor is wrong. */
    private static final long HALF_DAY_SECONDS = 43_200L;

    private static final long SECONDS_PER_DAY = 86_400L;

    /** How many of the newest data rows a spanning header cell is disambiguated against. */
    private static final int LAYOUT_SAMPLE_ROWS = 5;

    /** Logical reading columns every usable history table has to name. */
    private static final EnumSet<LogicalColumn> REQUIRED_COLUMNS = EnumSet.allOf(LogicalColumn.class);

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

    /** The upstream answers with a page, so the call asks for HTML and names a browser. */
    @Override
    public Map<String, String> requestHeaders(String sensorId) {
        return Map.of(
                HttpHeaders.USER_AGENT, USER_AGENT,
                HttpHeaders.ACCEPT, ACCEPT_HTML,
                HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE);
    }

    @Override
    public Try<List<SensorDataDTO>> extractTimedReadings(
            String sensorId, String response, int readingWindowSeconds, int numberOfReadings) {
        return parseStage(response, readingWindowSeconds).flatMap(outcome -> {
            if (outcome.unproductive()) {
                logUnproductive(sensorId, outcome);
            }
            return buildTimedReadings(
                    outcome.measurements(),
                    readingWindowSeconds,
                    numberOfReadings,
                    this::getLastReading,
                    (data, window, count) ->
                            getReadingsByInterval(data, window, count, OneChipMeasurement::timestamp, this::mapToDTO));
        });
    }

    @Override
    public SensorDataDTO mapToDTO(OneChipMeasurement m) {
        return new SensorDataDTO(m.gust(), m.avg(), m.min(), m.dir(), m.timestamp());
    }

    @Override
    public SensorDataDTO getLastReading(List<OneChipMeasurement> data) {
        return mapToDTO(data.getLast());
    }

    /** Package-private seam over "current time" so the future guard is deterministic in unit tests. */
    long currentTimeSeconds() {
        return Instant.now().getEpochSecond();
    }

    // ------------------------------------------------------------------------------------------------
    // Parse stage: tables, layout, rows, classification
    // ------------------------------------------------------------------------------------------------

    private Try<ParseOutcome> parseStage(String response, int readingWindowSeconds) {
        try {
            return Try.success(parseMeasurements(response, readingWindowSeconds));
        } catch (RuntimeException e) {
            return Try.success(ParseOutcome.parseFailed(e, bodyLength(response)));
        }
    }

    /**
     * Reads every history table the page offers and classifies the response when nothing came out of it.
     * Package-private so tests can assert the reason code and counters instead of reading log output.
     */
    ParseOutcome parseMeasurements(String html, int readingWindowSeconds) {
        var bodyLength = bodyLength(html);
        if (html == null || html.isBlank()) {
            return ParseOutcome.empty(ParseFailure.NO_BODY, TableSource.NONE, 0, 0, 0, bodyLength);
        }

        var document = Jsoup.parse(html);
        var tablesSeen = document.select("table").size();
        if (tablesSeen == 0) {
            return ParseOutcome.empty(ParseFailure.EMPTY_PAGE_SHELL, TableSource.NONE, 0, 0, 0, bodyLength);
        }

        var anchorDate = extractArrivedDate(html);
        var reads = historyTables(document).stream()
                .map(table -> readTable(table, anchorDate))
                .toList();
        var candidateRows = reads.stream().mapToInt(TableRead::candidateRows).sum();

        var resolved = reads.stream().filter(TableRead::resolved).toList();
        if (resolved.isEmpty()) {
            return ParseOutcome.empty(
                    ParseFailure.NO_DATA_TABLE, TableSource.NONE, tablesSeen, candidateRows, 0, bodyLength);
        }

        var validRows =
                resolved.stream().mapToInt(read -> read.measurements().size()).sum();
        if (validRows == 0) {
            return ParseOutcome.empty(
                    ParseFailure.NO_VALID_ROWS, TableSource.NONE, tablesSeen, candidateRows, 0, bodyLength);
        }

        var merged = mergeTables(resolved, readingWindowSeconds);
        return ParseOutcome.of(
                applyFutureGuard(merged.measurements()),
                merged.source(),
                tablesSeen,
                candidateRows,
                validRows,
                bodyLength);
    }

    private static int bodyLength(String html) {
        return html == null ? 0 : html.length();
    }

    private void logUnproductive(String sensorId, ParseOutcome outcome) {
        log.warn(
                "onechip response produced no readings: reason={}, url={}, bodyLength={}, tablesSeen={},"
                        + " candidateRows={}, validRows={}",
                outcome.failure().getOrElse(ParseFailure.PARSE_ERROR),
                getCallUrl(sensorId),
                outcome.bodyLength(),
                outcome.tablesSeen(),
                outcome.candidateRows(),
                outcome.validRows(),
                outcome.cause().getOrNull());
    }

    /** The page's history tables, recent first: the grouped table is a fallback and an extension. */
    private static List<HistoryTable> historyTables(Document document) {
        var tables = new ArrayList<HistoryTable>();
        tableIn(document, RECENT_TABLE_DIV).forEach(table -> tables.add(new HistoryTable(TableSource.RECENT, table)));
        tableIn(document, GROUPED_TABLE_DIV).forEach(table -> tables.add(new HistoryTable(TableSource.GROUPED, table)));
        return tables;
    }

    private static Option<Element> tableIn(Document document, String divId) {
        return Option.of(document.getElementById(divId)).flatMap(div -> Option.of(div.selectFirst("table")));
    }

    /** Resolves one candidate table: its column layout from the header captions, then its rows. */
    private TableRead readTable(HistoryTable candidate, LocalDate anchorDate) {
        var rows = candidate.table().select("tr");
        Option<ColumnLayout> layout = Option.none();
        int headerIndex = -1;
        for (int i = 0; i < rows.size() && layout.isEmpty(); i++) {
            var resolved = resolveLayout(rows, i);
            if (resolved.isDefined()) {
                layout = resolved;
                headerIndex = i;
            }
        }
        if (layout.isEmpty()) {
            return TableRead.unresolved(candidate.source(), rows.size());
        }

        var dataRows = dataCells(rows, headerIndex);
        return new TableRead(
                candidate.source(), layout, dataRows.size(), readDataRows(dataRows, layout.get(), anchorDate));
    }

    /** Cells of the rows below {@code headerIndex}; each row keeps its own cell order. */
    private static List<List<Element>> dataCells(List<Element> rows, int headerIndex) {
        var cellsOfRow = new ArrayList<List<Element>>();
        for (int i = headerIndex + 1; i < rows.size(); i++) {
            var cells = rows.get(i).select("td, th");
            if (!cells.isEmpty()) {
                cellsOfRow.add(cells);
            }
        }
        return cellsOfRow;
    }

    /**
     * Expands the header row through {@code colspan} into logical column slots and maps each caption to
     * the reading column it names. A spanning cell hides several real columns, so those groups are
     * disambiguated against the newest data rows: the direction group holds a compass name and the
     * degrees value, the gust group holds the speed and a secondary measure.
     */
    private static Option<ColumnLayout> resolveLayout(List<Element> rows, int headerIndex) {
        var slots = slotsByCaption(rows.get(headerIndex).select("td, th"));
        if (!slots.keySet().containsAll(REQUIRED_COLUMNS)) {
            return Option.none();
        }

        var dataRows = dataCells(rows, headerIndex);

        var indices = new EnumMap<LogicalColumn, Integer>(LogicalColumn.class);
        for (var entry : slots.entrySet()) {
            var group = entry.getValue();
            Option<Integer> index = group.size() == 1
                    ? Option.of(group.getFirst())
                    : pickNumericSlot(group, dataRows).orElse(Option.of(group.getFirst()));
            if (index.isEmpty()) {
                return Option.none();
            }
            indices.put(entry.getKey(), index.get());
        }

        return Option.of(new ColumnLayout(
                indices.get(LogicalColumn.TIME),
                indices.get(LogicalColumn.MIN),
                indices.get(LogicalColumn.AVG),
                indices.get(LogicalColumn.GUST),
                indices.get(LogicalColumn.DIRECTION)));
    }

    private static Map<LogicalColumn, List<Integer>> slotsByCaption(List<Element> headerCells) {
        var slots = new EnumMap<LogicalColumn, List<Integer>>(LogicalColumn.class);
        int slot = 0;
        for (Element cell : headerCells) {
            var span = Math.max(1, colspanOf(cell));
            var column = captionColumnOf(cell.text());
            if (column != null && !slots.containsKey(column)) {
                slots.put(column, IntStream.range(slot, slot + span).boxed().toList());
            }
            slot += span;
        }
        return slots;
    }

    private static int colspanOf(Element cell) {
        try {
            return Integer.parseInt(cell.attr("colspan").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Maps a header caption onto the reading column it names. Captions that name no reading column —
     * the compass-arrow {@code Dir}, temperature, signal level — map to nothing, which is also what makes
     * a renamed layout fail instead of silently mapping to the wrong cells.
     */
    private static LogicalColumn captionColumnOf(String caption) {
        var text = caption.toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.contains("hh:mm")) {
            return LogicalColumn.TIME;
        }
        if (isDegreesCaption(text)) {
            return LogicalColumn.DIRECTION;
        }
        if (text.contains("gust")) {
            return LogicalColumn.GUST;
        }
        if (text.contains("avg")) {
            return LogicalColumn.AVG;
        }
        if (text.contains("min")) {
            return LogicalColumn.MIN;
        }
        return null;
    }

    /** {@code Dir, °} carries the degrees value; a plain {@code Dir} carries a compass arrow. */
    private static boolean isDegreesCaption(String text) {
        return text.contains("dir") && (text.indexOf(DEGREE_SIGN) >= 0 || text.contains("deg"));
    }

    /** Leftmost slot of a spanning group whose sampled rows all hold a number in it. */
    private static Option<Integer> pickNumericSlot(List<Integer> group, List<List<Element>> dataRows) {
        var sample = dataRows.stream().limit(LAYOUT_SAMPLE_ROWS).toList();
        for (var slot : group) {
            if (isNumericColumn(slot, sample)) {
                return Option.of(slot);
            }
        }
        return Option.none();
    }

    private static boolean isNumericColumn(int slot, List<List<Element>> sample) {
        var reaching = sample.stream().filter(cells -> slot < cells.size()).toList();
        return !reaching.isEmpty()
                && reaching.stream()
                        .allMatch(cells -> numericValue(cells.get(slot)).isDefined());
    }

    /** Reads the data rows through the resolved layout, oldest-last page order turned into oldest-first. */
    private List<OneChipMeasurement> readDataRows(
            List<List<Element>> dataRows, ColumnLayout layout, LocalDate anchorDate) {
        var measurements = new ArrayList<OneChipMeasurement>();
        LocalDateTime previous = null; // the page lists rows newest-first
        for (var cells : dataRows) {
            if (cells.size() < layout.requiredCells()) {
                continue; // row too short to map
            }
            var values = parseRowValues(cells, layout);
            if (values.isEmpty()) {
                continue;
            }
            var parsed = values.get();
            var date = resolveRowDate(parsed.time(), anchorDate, previous);
            var rowDateTime = LocalDateTime.of(date, parsed.time());
            previous = rowDateTime;
            measurements.add(new OneChipMeasurement(
                    parsed.min(),
                    parsed.avg(),
                    parsed.gust(),
                    parsed.direction(),
                    rowDateTime.atZone(zone).toEpochSecond()));
        }
        return measurements.reversed();
    }

    private static Option<RowValues> parseRowValues(List<Element> cells, ColumnLayout layout) {
        return parseTime(cells.get(layout.time())).flatMap(time -> numericValue(cells.get(layout.min()))
                .flatMap(min -> numericValue(cells.get(layout.avg()))
                        .flatMap(avg -> numericValue(cells.get(layout.gust()))
                                .flatMap(gust -> numericValue(cells.get(layout.direction()))
                                        .map(direction -> new RowValues(time, min, avg, gust, direction))))));
    }

    private static Option<LocalTime> parseTime(Element cell) {
        try {
            return Option.of(LocalTime.parse(cell.text().trim(), ROW_TIME_FORMATTER));
        } catch (DateTimeParseException e) {
            return Option.none();
        }
    }

    private static Option<Float> numericValue(Element cell) {
        var text = cell.text().replace(DEGREE_SIGN, ' ').trim();
        if (text.isEmpty()) {
            return Option.none();
        }
        try {
            return Option.of(Float.parseFloat(text));
        } catch (NumberFormatException e) {
            return Option.none();
        }
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
     * Calendar date the newest row belongs to, taken from the page's "Arrived" line — which the page prints
     * inside a table row rather than in a heading. Without a parseable one the current date anchors the
     * series, and the future guard below keeps that guess from producing readings from the future.
     */
    private LocalDate extractArrivedDate(String html) {
        Matcher matcher = ARRIVED_DATE_PATTERN.matcher(html);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), ARRIVED_DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                log.warn("Could not parse onechip 'Arrived' date '{}'; falling back to today", matcher.group(1), e);
            }
        }
        return LocalDate.now(zone);
    }

    // ------------------------------------------------------------------------------------------------
    // Table selection and window-driven extension
    // ------------------------------------------------------------------------------------------------

    /**
     * The recent table is used alone when it already covers the requested window. When it has no usable
     * rows, or its span is narrower than the window (a roughly three-hour table cannot answer a four-hour
     * request), grouped rows are merged in; where both tables cover the same instant the finer-grained
     * recent row wins, and the merged series comes out ascending.
     */
    private MergedSeries mergeTables(List<TableRead> resolvedReads, int readingWindowSeconds) {
        var primary = resolvedReads.getFirst();
        var primaryRows = primary.measurements();
        boolean extendIntoGrouped =
                primaryRows.isEmpty() || (readingWindowSeconds > 0 && spanSeconds(primaryRows) < readingWindowSeconds);

        var byTimestamp = new LinkedHashMap<Long, OneChipMeasurement>();
        var contributed = new ArrayList<TableSource>();
        if (absorb(byTimestamp, primaryRows)) {
            contributed.add(primary.source());
        }
        if (extendIntoGrouped) {
            for (var read : resolvedReads.subList(1, resolvedReads.size())) {
                if (absorb(byTimestamp, read.measurements())) {
                    contributed.add(read.source());
                }
            }
        }

        var merged = byTimestamp.values().stream()
                .sorted(Comparator.comparingLong(OneChipMeasurement::timestamp))
                .collect(Collectors.toList());
        return new MergedSeries(merged, TableSource.of(contributed));
    }

    /** Adds rows that are not already present; returns whether at least one row was new. */
    private static boolean absorb(Map<Long, OneChipMeasurement> byTimestamp, List<OneChipMeasurement> rows) {
        boolean added = false;
        for (var row : rows) {
            if (byTimestamp.putIfAbsent(row.timestamp(), row) == null) {
                added = true;
            }
        }
        return added;
    }

    private static long spanSeconds(List<OneChipMeasurement> measurements) {
        if (measurements.size() < 2) {
            return 0L;
        }
        return measurements.getLast().timestamp() - measurements.getFirst().timestamp();
    }

    // ------------------------------------------------------------------------------------------------
    // Timestamp correction
    // ------------------------------------------------------------------------------------------------

    /**
     * A reading cannot be from the future, but how far the series may move depends on what the overshoot
     * says about the evidence. Within {@link #FUTURE_TOLERANCE_SECONDS} nothing is corrected; below half a
     * day the overshoot is clock or zone skew, so only the offending readings are clamped to "now" —
     * shifting them by a day would turn fresh data into roughly 22-hour-old data; at half a day or more the
     * "Arrived" anchor itself is wrong, so the whole series rolls back by the minimum number of whole days
     * that brings it within tolerance, preserving the spacing between readings.
     */
    private List<OneChipMeasurement> applyFutureGuard(List<OneChipMeasurement> measurements) {
        if (measurements.isEmpty()) {
            return measurements;
        }
        long now = currentTimeSeconds();
        long newest = measurements.stream()
                .mapToLong(OneChipMeasurement::timestamp)
                .max()
                .orElseThrow();
        long overshoot = newest - now;

        if (overshoot <= FUTURE_TOLERANCE_SECONDS) {
            return measurements;
        }

        if (overshoot < HALF_DAY_SECONDS) {
            log.warn(
                    "onechip readings are {} s newer than the current time; clamping them to now instead of moving"
                            + " the series by whole days",
                    overshoot);
            return measurements.stream()
                    .map(m -> m.timestamp() > now ? withTimestamp(m, now) : m)
                    .toList();
        }

        long days = (overshoot - FUTURE_TOLERANCE_SECONDS + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY;
        long shift = days * SECONDS_PER_DAY;
        log.warn(
                "onechip 'Arrived' anchor is {} s ahead of the current time; shifting the whole series back by"
                        + " {} day(s)",
                overshoot,
                days);
        return measurements.stream()
                .map(m -> withTimestamp(m, m.timestamp() - shift))
                .toList();
    }

    private static OneChipMeasurement withTimestamp(OneChipMeasurement m, long timestamp) {
        return new OneChipMeasurement(m.min(), m.avg(), m.gust(), m.dir(), timestamp);
    }

    // ------------------------------------------------------------------------------------------------
    // Outcome vocabulary
    // ------------------------------------------------------------------------------------------------

    /** Why a 1chip response produced no readings. */
    enum ParseFailure {
        NO_BODY,
        EMPTY_PAGE_SHELL,
        NO_DATA_TABLE,
        NO_VALID_ROWS,
        PARSE_ERROR
    }

    /** Which of the page's history tables the returned rows came from. */
    enum TableSource {
        NONE,
        RECENT,
        GROUPED,
        RECENT_AND_GROUPED;

        static TableSource of(List<TableSource> contributed) {
            if (contributed.isEmpty()) {
                return NONE;
            }
            if (contributed.size() == 1) {
                return contributed.getFirst();
            }
            return RECENT_AND_GROUPED;
        }
    }

    /**
     * What parsing produced: the readings in ascending order, the table they came from and the counters a
     * diagnostic has to report. {@code failure} is defined exactly when no reading was produced.
     */
    record ParseOutcome(
            List<OneChipMeasurement> measurements,
            Option<ParseFailure> failure,
            TableSource source,
            int tablesSeen,
            int candidateRows,
            int validRows,
            int bodyLength,
            Option<Throwable> cause) {

        static ParseOutcome of(
                List<OneChipMeasurement> measurements,
                TableSource source,
                int tablesSeen,
                int candidateRows,
                int validRows,
                int bodyLength) {
            return new ParseOutcome(
                    measurements,
                    Option.none(),
                    source,
                    tablesSeen,
                    candidateRows,
                    validRows,
                    bodyLength,
                    Option.none());
        }

        static ParseOutcome empty(
                ParseFailure failure,
                TableSource source,
                int tablesSeen,
                int candidateRows,
                int validRows,
                int bodyLength) {
            return new ParseOutcome(
                    List.of(),
                    Option.of(failure),
                    source,
                    tablesSeen,
                    candidateRows,
                    validRows,
                    bodyLength,
                    Option.none());
        }

        static ParseOutcome parseFailed(Throwable cause, int bodyLength) {
            return new ParseOutcome(
                    List.of(),
                    Option.of(ParseFailure.PARSE_ERROR),
                    TableSource.NONE,
                    0,
                    0,
                    0,
                    bodyLength,
                    Option.of(cause));
        }

        boolean unproductive() {
            return measurements.isEmpty();
        }
    }

    private record HistoryTable(TableSource source, Element table) {}

    /** A candidate table's contribution: the layout its header resolved to (if any) and its rows. */
    private record TableRead(
            TableSource source, Option<ColumnLayout> layout, int candidateRows, List<OneChipMeasurement> measurements) {

        static TableRead unresolved(TableSource source, int candidateRows) {
            return new TableRead(source, Option.none(), candidateRows, List.of());
        }

        boolean resolved() {
            return layout.isDefined();
        }
    }

    private record MergedSeries(List<OneChipMeasurement> measurements, TableSource source) {}

    /** Concrete cell index per logical column, resolved from a table's header captions. */
    private record ColumnLayout(int time, int min, int avg, int gust, int direction) {

        /** A row has to reach this many cells for every mapped column to be readable. */
        int requiredCells() {
            return Math.max(Math.max(time, direction), Math.max(Math.max(min, avg), gust)) + 1;
        }
    }

    private enum LogicalColumn {
        TIME,
        MIN,
        AVG,
        GUST,
        DIRECTION
    }

    /** Parsed values from a single past-data row, before its calendar date is resolved. */
    private record RowValues(LocalTime time, float min, float avg, float gust, float direction) {}
}
