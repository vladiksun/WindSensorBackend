package com.vb.wingfoil.provider.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OneChipDataProviderTest {

    private final OneChipDataProvider provider = createProvider();

    /**
     * The zone the 1chip.ru page prints times in (sensor-local, Egypt). Pinned here so the test is
     * deterministic on any machine (including CI running in UTC) and guards against regressions to
     * a system-default-zone conversion.
     */
    private static final ZoneId ZONE = ZoneId.of("Africa/Cairo");

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

    private static String readFixture() throws IOException {
        var stream = OneChipDataProviderTest.class.getResourceAsStream("/onechip-windt.html");
        assertNotNull(stream);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long ts(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 6, hour, minute).atZone(ZONE).toEpochSecond();
    }

    private static long tsAt(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toEpochSecond();
    }

    private static List<SensorDataDTO> readings(OneChipDataProvider provider, String body, int window, int count) {
        return provider.extractTimedReadings("0002", body, window, count).get();
    }

    @Test
    void buildsCallUrlFromSensorId() {
        assertEquals("https://1chip.ru/windt.php?id=0002", provider.getCallUrl("0002"));
    }

    @Test
    void parsesRowsIntoNormalizedReadingsInAscendingOrder() throws Exception {
        var result = readings(provider, readFixture(), 86400, 5);

        assertEquals(4, result.size());

        var oldest = result.getFirst();
        assertEquals(ts(20, 6), oldest.timestamp());
        assertEquals(6F, oldest.windMin(), 0.001f);
        assertEquals(10F, oldest.windAvg(), 0.001f);
        assertEquals(14F, oldest.windMax(), 0.001f);
        assertEquals(300F, oldest.windDirection(), 0.001f);

        var newest = result.getLast();
        assertEquals(ts(23, 6), newest.timestamp());
        assertEquals(12F, newest.windMin(), 0.001f);
        assertEquals(18F, newest.windAvg(), 0.001f);
        assertEquals(24F, newest.windMax(), 0.001f);
        assertEquals(360F, newest.windDirection(), 0.001f);

        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).timestamp() <= result.get(i).timestamp(), "readings must be ascending");
        }
    }

    @Test
    void returnsOnlyLatestReadingWhenNoWindowRequested() throws Exception {
        var result = readings(provider, readFixture(), 0, 0);

        assertEquals(1, result.size());
        var latest = result.getFirst();
        assertEquals(ts(23, 6), latest.timestamp());
        assertEquals(24F, latest.windMax(), 0.001f);
        assertEquals(360F, latest.windDirection(), 0.001f);
    }

    @Test
    void returnsSingleEmptyReadingForBlankBody() {
        assertEquals(List.of(SensorDataDTO.empty()), readings(provider, "", 3600, 5));
        assertEquals(List.of(SensorDataDTO.empty()), readings(provider, null, 3600, 5));
    }

    @Test
    void returnsSingleEmptyReadingWhenNoParseableRows() {
        var html = """
                <html><body>
                <div>Arrived: 06/09/2026 23:06:03</div>
                <div id="data_table"><table>
                <tr><th>hh:mm</th><th colspan="2">Wind</th><th></th><th></th><th></th><th></th><th></th><th></th></tr>
                </table></div>
                </body></html>
                """;
        var result = readings(provider, html, 3600, 5);
        assertEquals(List.of(SensorDataDTO.empty()), result);
    }

    @Test
    void skipsMalformedRowsAndReturnsValidOnes() {
        var html = """
                <html><body>
                <div>Arrived: 06/09/2026 23:06:03</div>
                <div id="data_table"><table>
                <tr><th>hh:mm</th><th colspan="2">Wind</th><th></th><th></th><th></th><th></th><th></th><th></th></tr>
                <tr><td>23:06</td><td>a</td><td>12</td><td>18</td><td>24</td><td></td><td>NNW</td><td>360\u00B0</td><td>25</td><td></td></tr>
                <tr><td>22:06</td><td>a</td><td>abc</td><td>15</td><td>20</td><td></td><td>WNW</td><td>340\u00B0</td><td>25</td><td></td></tr>
                <tr><td>21:06</td><td>a</td><td>8</td><td>12</td><td>17</td><td></td><td>WSW</td><td>320\u00B0</td><td>24</td><td></td></tr>
                </table></div>
                </body></html>
                """;
        var result = readings(provider, html, 86400, 5);

        assertEquals(2, result.size());
        assertEquals(ts(21, 6), result.getFirst().timestamp());
        assertEquals(ts(23, 6), result.getLast().timestamp());
    }

    @Test
    void rollsBackAcrossMidnightForMultiDayTable() {
        var html = """
                <html><body>
                <div>Arrived: 06/09/2026 00:30:00</div>
                <div id="data_table"><table>
                <tr><th>hh:mm</th><th colspan="2">Wind</th><th></th><th></th><th></th><th></th><th></th><th></th></tr>
                <tr><td>00:30</td><td>a</td><td>5</td><td>8</td><td>12</td><td></td><td>NNW</td><td>340\u00B0</td><td>25</td><td></td></tr>
                <tr><td>23:30</td><td>a</td><td>4</td><td>7</td><td>10</td><td></td><td>N</td><td>350\u00B0</td><td>25</td><td></td></tr>
                <tr><td>22:30</td><td>a</td><td>3</td><td>6</td><td>9</td><td></td><td>NNE</td><td>10\u00B0</td><td>24</td><td></td></tr>
                </table></div>
                </body></html>
                """;
        var result = readings(provider, html, 86400, 5);

        // Oldest is 05/09 22:30, then 05/09 23:30, then 06/09 00:30 (ascending across midnight).
        assertEquals(3, result.size());
        assertEquals(tsAt(2026, 9, 5, 22, 30), result.getFirst().timestamp());
        assertEquals(tsAt(2026, 9, 5, 23, 30), result.get(1).timestamp());
        assertEquals(tsAt(2026, 9, 6, 0, 30), result.getLast().timestamp());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(
                    result.get(i - 1).timestamp() < result.get(i).timestamp(), "readings must be strictly ascending");
        }
    }

    @Test
    void neverReturnsAFutureReading() throws Exception {
        long pinnedNow = tsAt(2026, 9, 6, 10, 0);
        var guarded = new OneChipDataProvider(createConfig(), null) {
            @Override
            long currentTimeSeconds() {
                return pinnedNow;
            }
        };
        var result =
                guarded.extractTimedReadings("0002", readFixture(), 86400, 5).get();

        assertFalse(result.isEmpty());
        for (var r : result) {
            assertTrue(r.timestamp() <= pinnedNow, "no reading may be in the future: " + r.timestamp());
        }
        // The fixture's newest reading (06/09 23:06) is shifted back one full day to 05/09 23:06.
        assertEquals(tsAt(2026, 9, 5, 23, 6), result.getLast().timestamp());
    }
}
