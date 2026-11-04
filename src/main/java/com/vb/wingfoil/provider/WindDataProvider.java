package com.vb.wingfoil.provider;

import com.vb.wingfoil.SensorDataDTO;
import io.vavr.control.Try;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface WindDataProvider<T> {

    String getName();

    String getUrl();

    String getCallUrl(String sensorId);

    /**
     * Extra headers this provider requires on the upstream call; empty by default. Providers whose
     * upstream demands more than {@code Accept} override it — Windguru answers 401 unless the request
     * names a windguru.cz page as its referring page.
     */
    default Map<String, String> requestHeaders(String sensorId) {
        return Map.of();
    }

    Try<List<SensorDataDTO>> extractTimedReadings(
            String sensorId, String response, int readingWindowSeconds, int numberOfReadings) throws IOException;

    SensorDataDTO mapToDTO(T measurement);

    SensorDataDTO getLastReading(List<T> data);
}
