package com.vb.wingfoil;

import io.vavr.control.Try;

import java.io.IOException;

public interface WindDataProvider {

    String getName();

    String getUrl();

    String getCallUrl(String sensorId);

    Try<SensorDataDTO> extractLastReading(String response) throws IOException;
}
