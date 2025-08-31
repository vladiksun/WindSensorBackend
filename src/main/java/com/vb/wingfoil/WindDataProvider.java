package com.vb.wingfoil;

import java.io.IOException;

public interface WindDataProvider {

    String getName();

    String getUrl();

    String getCallUrl(String sensorId);

    SensorDTO parseResponse(String response) throws IOException;

}
