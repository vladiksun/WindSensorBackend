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
