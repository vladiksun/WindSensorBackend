package com.vb.wingfoil.provider.impl;

import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OneChipDataProviderTest {

    private final OneChipDataProvider provider = createProvider();

    private static OneChipDataProvider createProvider() {
        var config = new WindDataProviderConfig(OneChipDataProvider.NAME);
        config.setUrl("https://1chip.ru/windt.php?id=%s");
        // The ObjectMapper is unused by this HTML-based provider, so a null instance is safe in this unit test.
        return new OneChipDataProvider(config, null);
    }

    private static String readFixture() throws IOException {
        var stream = OneChipDataProviderTest.class.getResourceAsStream("/onechip-windt.html");
        assertNotNull(stream);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long ts(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 6, hour, minute)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
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
}
