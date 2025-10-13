package com.vb.wingfoil;

import com.vb.wingfoil.provider.WindDataProvider;
import io.vavr.control.Option;

/**
 * Context object to encapsulate parameters for handling HTTP responses
 * when fetching sensor data.
 */
public record ResponseHandlerContext(
        Option<Integer> readingWindow,
        Option<Integer> numberOfReadings,
        SensorDTO sensor,
        String url,
        WindDataProvider<?> provider,
        String sensorId
) {
}