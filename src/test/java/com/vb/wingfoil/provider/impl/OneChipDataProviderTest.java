package com.vb.wingfoil.provider.impl;

import static com.vb.wingfoil.provider.impl.OneChipDataProvider.ParseFailure.EMPTY_PAGE_SHELL;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.ParseFailure.NO_BODY;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.ParseFailure.NO_DATA_TABLE;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.ParseFailure.NO_VALID_ROWS;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.TableSource.GROUPED;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.TableSource.NONE;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.TableSource.RECENT;
import static com.vb.wingfoil.provider.impl.OneChipDataProvider.TableSource.RECENT_AND_GROUPED;
import static org.junit.jupiter.api.Assertions.*;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import io.micronaut.http.HttpHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavior of the 1chip.ru HTML provider, based on pages captured from the live site rather than a synthetic
 * page shape: {@code onechip/windt-0264.html} and {@code onechip/windt-0001.html} are full captures,
 * {@code onechip/windt-empty-shell.html} is the body 1chip.ru answers when it has nothing to render, and the
 * remaining bodies are narrow mutations of a capture (renamed captions, an empty recent table).
 */
class OneChipDataProviderTest {

    /**
     * The zone the 1chip.ru page prints times in (sensor-local, Egypt). Pinned here so the test is
     * deterministic on any machine (including CI running in UTC) and guards against regressions to a
     * system-default-zone conversion.
     */
    private static final ZoneId ZONE = ZoneId.of("Africa/Cairo");

    private static final String LIVE_PAGE = "/onechip/windt-0264.html";
    private static final String OTHER_LIVE_PAGE = "/onechip/windt-0001.html";
    private static final String EMPTY_SHELL_PAGE = "/onechip/windt-empty-shell.html";
    private static final String GROUPED_ONLY_PAGE = "/onechip/windt-group-only.html";
    private static final String RENAMED_CAPTIONS_PAGE = "/onechip/windt-renamed-captions.html";

    /** The header row 1chip.ru actually prints: seven cells, two of them spanning two columns each. */
    private static final String REAL_HEADER = """
            <tr bgcolor="#E0E0E0"><td>hh:mm</td><td>Dir</td><td><small>min </small></td><td>Avg, m/s</td>\
             <td colspan="2">Gust, m/s</td><td colspan="2">Dir, &deg</td><td>Te,&deg;C</td></tr>\
            """;

    private final OneChipDataProvider provider = createProvider();

    private static OneChipDataProvider createProvider() {
        // The ObjectMapper is unused by this HTML-based provider, so a null instance is safe in this unit test.
        return new OneChipDataProvider(createConfig(), null);
    }

    private static WindDataProviderConfig createConfig() {
        var config = new WindDataProviderConfig(OneChipDataProvider.NAME);
        config.setUrl("https://1chip.ru/windt.php?id=%s");
        config.setTimezone(ZONE.getId());
        return config;
    }

    /** A provider whose "now" is fixed, so timestamp correction is asserted rather than timed. */
    private static OneChipDataProvider providerWithNow(long nowSeconds) {
        return new OneChipDataProvider(createConfig(), null) {
            @Override
            long currentTimeSeconds() {
                return nowSeconds;
            }
        };
    }

    private static String fixture(String resource) throws IOException {
        var stream = OneChipDataProviderTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing test fixture " + resource);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long tsAt(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toEpochSecond();
    }

    /** "Now" for the live captures: later than every row they contain, so no correction is expected. */
    private static long liveNow() {
        return tsAt(2026, 11, 17, 21, 0);
    }

    private static List<SensorDataDTO> readings(OneChipDataProvider provider, String body, int window, int count) {
        return provider.extractTimedReadings("0264", body, window, count).get();
    }

    /** A page built around the real header, newest-first rows, for the narrow synthetic cases. */
    private static String page(String arrived, String... dataRows) {
        var rows = String.join("", dataRows);
        return """
                <html><body>
                <table><tr><td>Arrived: %s</td></tr></table>
                <div id="data_table"><table border="1">%s%s</table></div>
                </body></html>
                """.formatted(arrived, REAL_HEADER, rows);
    }

    private static String row(String... cells) {
        var builder = new StringBuilder("<tr>");
        for (var cell : cells) {
            builder.append("<td>").append(cell).append("</td>");
        }
        return builder.append("</tr>").toString();
    }

    // -----------------------------------------------------------------------------------------------
    // Request identity for an HTML upstream
    // -----------------------------------------------------------------------------------------------

    @Test
    void buildsCallUrlFromSensorId() {
        assertEquals("https://1chip.ru/windt.php?id=0002", provider.getCallUrl("0002"));
    }

    @Test
    void declaresHtmlPageIdentityOnTheUpstreamCall() {
        Map<String, String> headers = provider.requestHeaders("0264");

        assertEquals(OneChipDataProvider.ACCEPT_HTML, headers.get(HttpHeaders.ACCEPT));
        assertEquals(OneChipDataProvider.ACCEPT_LANGUAGE, headers.get(HttpHeaders.ACCEPT_LANGUAGE));
        assertTrue(headers.get(HttpHeaders.USER_AGENT).startsWith("Mozilla/5.0"), "a browser-like user agent");
        assertTrue(headers.values().stream().allMatch(value -> value != null && !value.isBlank()));
    }

    // -----------------------------------------------------------------------------------------------
    // Live captures: values, ordering, window
    // -----------------------------------------------------------------------------------------------

    @Test
    void parsesCapturedLivePageIntoRealReadings() throws Exception {
        var pinned = providerWithNow(liveNow());

        var result = readings(pinned, fixture(LIVE_PAGE), 3600, 5);

        assertEquals(5, result.size());

        // Newest-first page, oldest-first answer: 18:38 is the oldest row inside a 3600 s window whose newest
        // reading is 19:36, and every value comes from the column its caption names.
        var oldest = result.getFirst();
        assertEquals(tsAt(2026, 11, 17, 18, 38), oldest.timestamp());
        assertEquals(10.1F, oldest.windMin(), 0.001f);
        assertEquals(11.6F, oldest.windAvg(), 0.001f);
        assertEquals(12.9F, oldest.windMax(), 0.001f);
        assertEquals(34F, oldest.windDirection(), 0.001f);

        var newest = result.getLast();
        assertEquals(tsAt(2026, 11, 17, 19, 36), newest.timestamp());
        assertEquals(7.5F, newest.windMin(), 0.001f);
        assertEquals(9.4F, newest.windAvg(), 0.001f);
        assertEquals(10.9F, newest.windMax(), 0.001f);
        // The degrees column ("Dir, °"), not the neighbouring cell holding a secondary measure.
        assertEquals(9F, newest.windDirection(), 0.001f);

        assertAscending(result);
        for (var reading : result) {
            assertTrue(reading.timestamp() <= liveNow(), "no reading may be in the future");
        }
    }

    @Test
    void parsesTheSecondCapturedLivePageIntoRealReadings() throws Exception {
        var pinned = providerWithNow(liveNow());

        var result = readings(pinned, fixture(OTHER_LIVE_PAGE), 3600, 5);

        assertEquals(5, result.size());
        var newest = result.getLast();
        assertEquals(tsAt(2026, 11, 17, 19, 36), newest.timestamp());
        assertEquals(7.4F, newest.windMin(), 0.001f);
        assertEquals(8.7F, newest.windAvg(), 0.001f);
        assertEquals(11.2F, newest.windMax(), 0.001f);
        // Cell five is the gust group's secondary measure (km/h); "Dir, °" is column seven.
        assertEquals(9F, newest.windDirection(), 0.001f);
        assertAscending(result);
    }

    @Test
    void returnsOnlyLatestReadingWhenNoWindowRequested() throws Exception {
        var pinned = providerWithNow(liveNow());

        var result = readings(pinned, fixture(LIVE_PAGE), 0, 0);

        assertEquals(1, result.size());
        assertEquals(tsAt(2026, 11, 17, 19, 36), result.getFirst().timestamp());
    }

    @Test
    void readsEveryRowOfTheRecentTableOldestFirst() throws Exception {
        // A window narrower than the recent table's own span (16:39 to 19:36) keeps that table alone.
        var outcome = providerWithNow(liveNow()).parseMeasurements(fixture(LIVE_PAGE), 10_000);

        assertEquals(RECENT, outcome.source());
        assertEquals(51, outcome.measurements().size());

        var oldest = outcome.measurements().getFirst();
        var newest = outcome.measurements().getLast();
        assertEquals(tsAt(2026, 11, 17, 16, 39), oldest.timestamp());
        assertEquals(8.8F, oldest.min(), 0.001f);
        assertEquals(10.4F, oldest.avg(), 0.001f);
        assertEquals(11.6F, oldest.gust(), 0.001f);
        assertEquals(36F, oldest.dir(), 0.001f);
        assertEquals(tsAt(2026, 11, 17, 19, 36), newest.timestamp());
        for (int i = 1; i < outcome.measurements().size(); i++) {
            assertTrue(
                    outcome.measurements().get(i - 1).timestamp()
                            < outcome.measurements().get(i).timestamp(),
                    "readings must be strictly ascending");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Header-driven column mapping and table selection
    // -----------------------------------------------------------------------------------------------

    @Test
    void rejectsATableWhoseSpeedCaptionsWereRenamed() throws Exception {
        var body = fixture(RENAMED_CAPTIONS_PAGE);
        var pinned = providerWithNow(liveNow());

        var outcome = pinned.parseMeasurements(body, 3600);

        // The rows are still there and still numeric; only the captions changed. Guessing from cell positions
        // would return numbers from the wrong columns, so nothing is returned and the reason is named.
        assertEquals(NO_DATA_TABLE, outcome.failure().get());
        assertEquals(3, outcome.tablesSeen(), "the page still carries its tables");
        assertEquals(0, outcome.validRows());
        assertEquals(NONE, outcome.source());
        assertEquals(body.length(), outcome.bodyLength());

        assertEquals(List.of(SensorDataDTO.empty()), readings(pinned, body, 3600, 5));
    }

    @Test
    void readsGroupedHistoryWhenTheRecentTableHasNoRows() throws Exception {
        var pinned = providerWithNow(liveNow());
        var body = fixture(GROUPED_ONLY_PAGE);

        var outcome = pinned.parseMeasurements(body, 3600);
        assertEquals(GROUPED, outcome.source());
        assertEquals(13, outcome.validRows());

        var result = readings(pinned, body, 3600, 5);
        assertEquals(5, result.size());

        var oldest = result.getFirst();
        assertEquals(tsAt(2026, 11, 17, 18, 30), oldest.timestamp());
        assertEquals(9.5F, oldest.windMin(), 0.001f);
        assertEquals(11.1F, oldest.windAvg(), 0.001f);
        assertEquals(13.2F, oldest.windMax(), 0.001f);
        assertEquals(27F, oldest.windDirection(), 0.001f);

        var newest = result.getLast();
        assertEquals(tsAt(2026, 11, 17, 19, 30), newest.timestamp());
        assertEquals(7.5F, newest.windMin(), 0.001f);
        assertEquals(9.5F, newest.windAvg(), 0.001f);
        assertEquals(11.5F, newest.windMax(), 0.001f);
        assertEquals(11F, newest.windDirection(), 0.001f);
        assertAscending(result);
    }

    @Test
    void mergesGroupedHistoryWhenTheRequestedWindowIsWiderThanTheRecentTable() throws Exception {
        var pinned = providerWithNow(liveNow());

        var outcome = pinned.parseMeasurements(fixture(LIVE_PAGE), 14_400);

        assertEquals(RECENT_AND_GROUPED, outcome.source());
        var merged = outcome.measurements();
        // The recent table stops at 16:39; the grouped table reaches 16:30, which a four-hour window needs.
        assertEquals(tsAt(2026, 11, 17, 16, 30), merged.getFirst().timestamp());
        assertEquals(tsAt(2026, 11, 17, 19, 36), merged.getLast().timestamp());

        var timestamps = new HashSet<Long>();
        for (var measurement : merged) {
            assertTrue(timestamps.add(measurement.timestamp()), "merged series must not duplicate an instant");
        }

        // Both tables carry 17:30; the finer-grained recent row (avg 10.4, gust 12.0) wins over the grouped
        // one (avg 10.5, gust 12.5).
        var sharedInstant = merged.stream()
                .filter(m -> m.timestamp() == tsAt(2026, 11, 17, 17, 30))
                .findFirst();
        assertTrue(sharedInstant.isPresent());
        assertEquals(10.4F, sharedInstant.get().avg(), 0.001f);
        assertEquals(12.0F, sharedInstant.get().gust(), 0.001f);

        var result = readings(pinned, fixture(LIVE_PAGE), 14_400, 8);
        assertEquals(tsAt(2026, 11, 17, 16, 30), result.getFirst().timestamp(), "the window must reach further back");
        assertAscending(result);
    }

    @Test
    void keepsUsingOnlyTheRecentTableWhenItAlreadyCoversTheWindow() throws Exception {
        var outcome = providerWithNow(liveNow()).parseMeasurements(fixture(LIVE_PAGE), 3600);

        assertEquals(RECENT, outcome.source());
        assertEquals(51, outcome.measurements().size(), "a three-hour recent table covers a one-hour window");
    }

    @Test
    void ignoresExtraTrailingCellsAndSkipsRowsTooShortToMap() {
        var body = page(
                "06/09/2026 23:06:03",
                // ten cells, as the recent table prints them
                row("23:06", "\u2197", "12", "18", "24", "36", "NNW", "360\u00B0", "25", ""),
                // nine cells: no trailing empty cell
                row("22:06", "\u2197", "10", "15", "20", "34", "WNW", "340\u00B0", "25"),
                // too short to reach the direction column
                row("21:06", "\u2197", "8", "12", "17"),
                row("20:06", "\u2197", "6", "10", "14", "30", "SW", "300\u00B0", "24", ""));

        var result = readings(provider, body, 86_400, 5);

        assertEquals(3, result.size());
        assertEquals(tsAt(2026, 9, 6, 20, 6), result.getFirst().timestamp());
        assertEquals(300F, result.getFirst().windDirection(), 0.001f);
        assertEquals(tsAt(2026, 9, 6, 23, 6), result.getLast().timestamp());
        assertEquals(24F, result.getLast().windMax(), 0.001f);
        // The nine-cell row is read through the same layout as the ten-cell ones: values are not shifted.
        var middle = result.get(1);
        assertEquals(tsAt(2026, 9, 6, 22, 6), middle.timestamp());
        assertEquals(10F, middle.windMin(), 0.001f);
        assertEquals(15F, middle.windAvg(), 0.001f);
        assertEquals(20F, middle.windMax(), 0.001f);
        assertEquals(340F, middle.windDirection(), 0.001f);
    }

    @Test
    void skipsMalformedRowsAndReturnsValidOnes() {
        var body = page(
                "06/09/2026 23:06:03",
                row("23:06", "\u2197", "12", "18", "24", "36", "NNW", "360\u00B0", "25", ""),
                row("22:06", "\u2197", "abc", "15", "20", "34", "WNW", "340\u00B0", "25", ""),
                row("21:06", "\u2197", "8", "12", "17", "32", "WSW", "320\u00B0", "24", ""));

        var result = readings(provider, body, 86_400, 5);

        assertEquals(2, result.size());
        assertEquals(tsAt(2026, 9, 6, 21, 6), result.getFirst().timestamp());
        assertEquals(tsAt(2026, 9, 6, 23, 6), result.getLast().timestamp());
    }

    @Test
    void rollsBackAcrossMidnightForMultiDayTable() {
        var body = page(
                "06/09/2026 00:30:00",
                row("00:30", "\u2197", "5", "8", "12", "25", "NNW", "340\u00B0", "25", ""),
                row("23:30", "\u2197", "4", "7", "10", "25", "N", "350\u00B0", "25", ""),
                row("22:30", "\u2197", "3", "6", "9", "24", "NNE", "10\u00B0", "24", ""));

        var result = readings(provider, body, 86_400, 5);

        // Oldest is 05/09 22:30, then 05/09 23:30, then 06/09 00:30 (ascending across midnight).
        assertEquals(3, result.size());
        assertEquals(tsAt(2026, 9, 5, 22, 30), result.getFirst().timestamp());
        assertEquals(tsAt(2026, 9, 5, 23, 30), result.get(1).timestamp());
        assertEquals(tsAt(2026, 9, 6, 0, 30), result.getLast().timestamp());
        assertAscending(result);
    }

    // -----------------------------------------------------------------------------------------------
    // Bounded timestamp correction
    // -----------------------------------------------------------------------------------------------

    @Test
    void leavesFreshDataAtTheTimesThePagePrints() throws Exception {
        var pinned = tsAt(2026, 11, 17, 19, 40);

        var result = readings(providerWithNow(pinned), fixture(LIVE_PAGE), 3600, 5);

        assertEquals(
                tsAt(2026, 11, 17, 19, 36), result.getLast().timestamp(), "four minutes old stays four minutes old");
    }

    @Test
    void clampsSmallSkewToNowInsteadOfMovingTheSeriesByADay() throws Exception {
        // The newest row (23:06) is 66 s past the tolerance but far short of half a day: clock or zone skew.
        var pinned = tsAt(2026, 9, 6, 22, 0);

        var result = readings(providerWithNow(pinned), syntheticFixture(), 86_400, 5);

        assertEquals(4, result.size());
        for (var reading : result) {
            assertTrue(reading.timestamp() <= pinned, "no reading may be in the future: " + reading.timestamp());
        }
        // Only the readings newer than "now" are clamped; nothing moves by a day, so the rows that were already
        // older keep the times the page printed instead of landing 24 hours earlier.
        assertEquals(tsAt(2026, 9, 6, 20, 6), result.getFirst().timestamp());
        assertEquals(tsAt(2026, 9, 6, 21, 6), result.get(1).timestamp());
        assertEquals(pinned, result.getLast().timestamp());
    }

    @Test
    void neverReturnsAFutureReading() throws Exception {
        long pinnedNow = tsAt(2026, 9, 6, 10, 0); // thirteen hours before the newest row: a wrong date anchor

        var result = readings(providerWithNow(pinnedNow), syntheticFixture(), 86_400, 5);

        assertFalse(result.isEmpty());
        for (var r : result) {
            assertTrue(r.timestamp() <= pinnedNow, "no reading may be in the future: " + r.timestamp());
        }
        // The fixture's newest reading (06/09 23:06) is shifted back one full day to 05/09 23:06.
        assertEquals(tsAt(2026, 9, 5, 23, 6), result.getLast().timestamp());
    }

    @Test
    void rollsTheSeriesBackByEveryWholeDayItHasTo() throws Exception {
        long pinnedNow = tsAt(2026, 9, 4, 23, 6); // the anchor looks exactly two days ahead

        var result = readings(providerWithNow(pinnedNow), syntheticFixture(), 86_400, 5);

        assertEquals(tsAt(2026, 9, 4, 23, 6), result.getLast().timestamp());
        assertEquals(tsAt(2026, 9, 4, 20, 6), result.getFirst().timestamp());
        assertAscending(result);
    }

    // -----------------------------------------------------------------------------------------------
    // Classification of unproductive responses
    // -----------------------------------------------------------------------------------------------

    @Test
    void namesAnEmptyPageShellAsAnEmptyPageShell() throws Exception {
        var body = fixture(EMPTY_SHELL_PAGE);

        var outcome = provider.parseMeasurements(body, 3600);

        assertEquals(12, body.length(), "the shell 1chip.ru answers with");
        assertEquals(EMPTY_PAGE_SHELL, outcome.failure().get());
        assertEquals(0, outcome.tablesSeen());
        assertEquals(0, outcome.validRows());
        assertEquals(body.length(), outcome.bodyLength());
    }

    @Test
    void namesAMissingOrBlankBodyAsNoBody() {
        assertEquals(NO_BODY, provider.parseMeasurements(null, 3600).failure().get());
        assertEquals(NO_BODY, provider.parseMeasurements("   ", 3600).failure().get());
    }

    @Test
    void namesATableWhoseRowsAreAllUnparseableAsNoValidRows() {
        var body = page(
                "06/09/2026 23:06:03",
                row("23:06", "\u2197", "-", "-", "-", "-", "NNW", "-", "25", ""),
                row("not a time", "\u2197", "-", "-", "-", "-", "N", "-", "25", ""));

        var outcome = provider.parseMeasurements(body, 3600);

        assertEquals(NO_VALID_ROWS, outcome.failure().get());
        assertTrue(outcome.candidateRows() > 0, "the rows were seen, they just carry no numbers");
    }

    @Test
    void keepsTheSingleZeroReadingForEveryUnproductiveResponse() throws Exception {
        var zero = List.of(SensorDataDTO.empty());

        assertEquals(zero, readings(provider, fixture(EMPTY_SHELL_PAGE), 3600, 5));
        assertEquals(zero, readings(provider, fixture(RENAMED_CAPTIONS_PAGE), 3600, 5));
        assertEquals(zero, readings(provider, "", 3600, 5));
        assertEquals(zero, readings(provider, page("06/09/2026 23:06:03"), 3600, 5));
    }

    @Test
    void neverMixesTheZeroReadingWithParsedOnes() throws Exception {
        var result = readings(providerWithNow(liveNow()), fixture(LIVE_PAGE), 86_400, 5);

        for (var reading : result) {
            assertNotEquals(SensorDataDTO.empty(), reading);
            assertTrue(reading.timestamp() != 0);
        }
    }

    // -----------------------------------------------------------------------------------------------

    /** The synthetic four-row page: rows at 20:06, 21:06, 22:06 and 23:06 on 06/09/2026. */
    private static String syntheticFixture() throws IOException {
        var stream = OneChipDataProviderTest.class.getResourceAsStream("/onechip-windt.html");
        assertNotNull(stream);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void assertAscending(List<SensorDataDTO> readings) {
        for (int i = 1; i < readings.size(); i++) {
            assertTrue(readings.get(i - 1).timestamp() <= readings.get(i).timestamp(), "readings must be ascending");
        }
    }
}
