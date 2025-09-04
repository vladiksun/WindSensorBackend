package com.vb.wingfoil;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.serde.annotation.Serdeable;

@ConfigurationProperties("wind-sensor")
public class WindSensorConfig {

    private String spotsDataUrl;

    private String spotsDataMediaType;

    public String getSpotsDataUrl() {
        return spotsDataUrl;
    }

    public void setSpotsDataUrl(String spotsDataUrl) {
        this.spotsDataUrl = spotsDataUrl;
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
    }

}
