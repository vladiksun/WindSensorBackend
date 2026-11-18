package com.vb.wingfoil.cache;

import com.vb.wingfoil.SensorDataDTO;
import java.util.List;

/**
 * What a {@code /sensor-data} request produced: the readings to return, and where they came from. The body a client
 * sees is unchanged by caching — only the {@code X-Cache} header reports which path filled it.
 */
public record SensorDataOutcome(List<SensorDataDTO> readings, CacheStatus status) {

    public SensorDataOutcome {
        readings = List.copyOf(readings);
    }
}
