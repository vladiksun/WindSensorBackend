package com.vb.wingfoil.provider.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the Windguru provider: call-URL shape, the mandatory {@code Referer}, unzipping the
 * columnar payload, and Windguru's gap/no-data quirks.
 * <p>
 * The provider and the serde {@code ObjectMapper} come from the application context so the wired bean is
 * exercised against the real {@code application.yml} template. Fixture: a trimmed live capture of station
 * 15693 (Dahab – Assalah) including its extra columns and a published gap.
 */
@MicronautTest
class WindguruDataProviderTest {

    private static final String STATION_ID = "15693";

    /** Row values of the fixture, oldest-first; index 3 is the row published with a null wind_avg. */
    private static final long GAP_TIMESTAMP = 1793786220L;

    private static final Pattern UTC_RANGE = Pattern.compile("&from=([0-9T:.Z-]+)&to=([0-9T:.Z-]+)$");

    private final WindguruDataProvider provider;

    private WindguruDataProviderTest(WindguruDataProvider provider) {
        this.provider = provider;
    }

    private static String readFixture() throws IOException {
        var stream = WindguruDataProviderTest.class.getResourceAsStream("/windguru/station-data.json");
        assertNotNull(stream, "missing windguru fixture");
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private List<SensorDataDTO> readings(String body, int window, int count) {
        return provider.extractTimedReadings(STATION_ID, body, window, count).get();
    }

    @Test
    void getCallUrlContainsStationIdAndUtcRange() {
        var url = provider.getCallUrl(STATION_ID);

        assertTrue(url.startsWith("https://www.windguru.cz/int/iapi.php?"), url);
        assertTrue(url.contains("q=station_data"), url);
        assertTrue(url.contains("id_station=" + STATION_ID), url);
        assertTrue(url.contains("avg_minutes=1"), url);
        assertFalse(url.contains("%s"), "the station-id placeholder must be substituted: " + url);

        var matcher = UTC_RANGE.matcher(url);
        assertTrue(matcher.find(), "call url must end with an ISO-UTC from/to range: " + url);

        var from = Instant.parse(matcher.group(1));
        var to = Instant.parse(matcher.group(2));
        assertTrue(from.isBefore(to), "from must precede to: " + url);
        assertEquals(WindguruDataProvider.LOOK_BACK_SECONDS, to.getEpochSecond() - from.getEpochSecond());
    }

    @Test
    void requestHeadersCarryStationPageReferer() {
        assertEquals(
                Map.of("Referer", "https://www.windguru.cz/station/" + STATION_ID),
                provider.requestHeaders(STATION_ID));
    }

    @Test
    void providersWithoutDeclaredHeadersStayOnTheDefault() {
        // The hook is a default method: a provider that does not override it declares nothing, so the
        // shared request builder adds no headers for it.
        var oneChip = new OneChipDataProvider(new WindDataProviderConfig(OneChipDataProvider.NAME), null);

        assertTrue(oneChip.requestHeaders("0002").isEmpty());
    }

    @Test
    void parsesColumnarPayloadIntoAscendingReadings() throws Exception {
        var result = readings(readFixture(), 86400, 100);

        // 8 published rows, one of them a gap -> 7 complete readings.
        assertEquals(7, result.size());

        for (int i = 1; i < result.size(); i++) {
            assertTrue(
                    result.get(i - 1).timestamp() < result.get(i).timestamp(), "readings must be strictly ascending");
        }
    }

    @Test
    void mapsPublishedFieldsWithoutConversion() throws Exception {
        var oldest = readings(readFixture(), 86400, 100).getFirst();

        assertEquals(1793785260L, oldest.timestamp());
        assertEquals(20.2f, oldest.windMin(), 0.001f);
        assertEquals(23.1f, oldest.windAvg(), 0.001f);
        assertEquals(26.0f, oldest.windMax(), 0.001f);
        assertEquals(10.0f, oldest.windDirection(), 0.001f);
    }

    @Test
    void skipsRowsWithMissingValues() throws Exception {
        var result = readings(readFixture(), 86400, 100);

        // The fixture keeps a null wind_avg at index 3 (the shape Windguru publishes for a gap).
        assertTrue(
                result.stream().noneMatch(r -> r.timestamp() == GAP_TIMESTAMP),
                "a row published with a missing value must not become a reading: " + result);
        assertEquals(7, result.size());
    }

    @Test
    void latestOnlyRequestReturnsTheNewestReading() throws Exception {
        var result = readings(readFixture(), 0, 0);

        assertEquals(1, result.size());
        var latest = result.getFirst();
        assertEquals(1793787360L, latest.timestamp());
        assertEquals(20.8f, latest.windMin(), 0.001f);
        assertEquals(24.3f, latest.windAvg(), 0.001f);
        assertEquals(28.7f, latest.windMax(), 0.001f);
        assertEquals(37.0f, latest.windDirection(), 0.001f);
    }

    @Test
    void windowedRequestHonoursReadingWindowAndCount() throws Exception {
        var result = readings(readFixture(), 3600, 5);

        // Every complete reading lies within 2100 s of the newest one, so the window keeps all of them
        // and the shared reducer thins them to 5 while preserving first and last.
        assertEquals(5, result.size());
        assertEquals(1793785260L, result.getFirst().timestamp());
        assertEquals(1793787360L, result.getLast().timestamp());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).timestamp() < result.get(i).timestamp(), "readings must be ascending");
        }
    }

    @Test
    void emptyArrayBodyReturnsSingleEmptyReading() {
        assertEquals(List.of(SensorDataDTO.empty()), readings("[]", 3600, 5));
        assertEquals(List.of(SensorDataDTO.empty()), readings("\n  [ ]  \n", 3600, 5));
    }

    @Test
    void blankBodyReturnsSingleEmptyReading() {
        assertEquals(List.of(SensorDataDTO.empty()), readings("", 3600, 5));
        assertEquals(List.of(SensorDataDTO.empty()), readings(null, 3600, 5));
    }

    @Test
    void payloadWithoutUsableColumnsReturnsSingleEmptyReading() {
        // A truncated/failed payload must not produce zero-speed readings.
        var result = readings("{\"unixtime\":[1793785260],\"wind_avg\":[null]}", 3600, 5);

        assertEquals(List.of(SensorDataDTO.empty()), result);
    }

    @Test
    void unparseablePayloadIsRecoveredAsNoData() {
        var result = readings("{ this is not json", 3600, 5);

        assertEquals(List.of(SensorDataDTO.empty()), result);
    }
}
