package com.vb.wingfoil.provider;

import com.vb.wingfoil.SensorDataDTO;
import io.vavr.control.Try;

import java.io.IOException;
import java.util.List;

public interface WindDataProvider {

    String getName();

    String getUrl();

    String getCallUrl(String sensorId);

    Try<List<SensorDataDTO>> extractTimedReadings(String sensorId,
                                                  String response,
                                                  int readingWindowSeconds,
                                                  int numberOfReadings) throws IOException;
}
