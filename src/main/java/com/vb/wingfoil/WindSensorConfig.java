package com.vb.wingfoil;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.serde.annotation.Serdeable;

@ConfigurationProperties("wind-sensor")
public class WindSensorConfig {

    private String spotsDataUrl;

    private String spotsTestDataUrl;

    private String spotsDahabUrl;

    private String spotsDataMediaType;

    /**
     * Outbound call bounds (seconds) for the shared upstream HttpClient. A provider whose endpoint accepts
     * the connection and then stalls must fail rather than pin a request thread, so all three are explicit.
     */
    private int upstreamConnectTimeoutSeconds = 10;

    private int upstreamResponseTimeoutSeconds = 30;

    private int upstreamConnectionRequestTimeoutSeconds = 10;

    public String getSpotsDataUrl() {
        return spotsDataUrl;
    }

    public void setSpotsDataUrl(String spotsDataUrl) {
        this.spotsDataUrl = spotsDataUrl;
    }

    public String getSpotsTestDataUrl() {
        return spotsTestDataUrl;
    }

    public void setSpotsTestDataUrl(String spotsTestDataUrl) {
        this.spotsTestDataUrl = spotsTestDataUrl;
    }

    public String getSpotsDahabUrl() {
        return spotsDahabUrl;
    }

    public void setSpotsDahabUrl(String spotsDahabUrl) {
        this.spotsDahabUrl = spotsDahabUrl;
    }

    public String getSpotsDataMediaType() {
        return spotsDataMediaType;
    }

    public void setSpotsDataMediaType(String spotsDataMediaType) {
        this.spotsDataMediaType = spotsDataMediaType;
    }

    public int getUpstreamConnectTimeoutSeconds() {
        return upstreamConnectTimeoutSeconds;
    }

    public void setUpstreamConnectTimeoutSeconds(int upstreamConnectTimeoutSeconds) {
        this.upstreamConnectTimeoutSeconds = upstreamConnectTimeoutSeconds;
    }

    public int getUpstreamResponseTimeoutSeconds() {
        return upstreamResponseTimeoutSeconds;
    }

    public void setUpstreamResponseTimeoutSeconds(int upstreamResponseTimeoutSeconds) {
        this.upstreamResponseTimeoutSeconds = upstreamResponseTimeoutSeconds;
    }

    public int getUpstreamConnectionRequestTimeoutSeconds() {
        return upstreamConnectionRequestTimeoutSeconds;
    }

    public void setUpstreamConnectionRequestTimeoutSeconds(int upstreamConnectionRequestTimeoutSeconds) {
        this.upstreamConnectionRequestTimeoutSeconds = upstreamConnectionRequestTimeoutSeconds;
    }

    @Serdeable
    @EachProperty("wind-providers")
    public static class WindDataProviderConfig {

        private String name;

        private String url;

        private String timezone;

        public WindDataProviderConfig(@Parameter String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }
}
