package com.vb.wingfoil;

import java.io.IOException;
import java.util.List;

public interface WindDataProvider {

    String getName();

    String getUrl();

    String getCallUrl(String sensorId);

    SensorDataDTO parseSensorDataResponse(String response) throws IOException;

    List<SpotData> parseSpotsDataResponse(String response) throws IOException;

}
