package com.vb.wingfoil.cache;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Configuration of the {@code POST /sensor-data} response cache ({@code wind-sensor.sensor-data-cache.*} in
 * {@code application.yml}).
 *
 * <p>The defaults are the shipped production values: a five-minute base validity, a one-minute floating window
 * around it, and caching enabled. Both durations are overridable per deployment through {@code CACHE_TTL} and
 * {@code CACHE_TTL_FLOATING_WINDOW}; the enable flag is an ordinary Micronaut property, so setting
 * {@code wind-sensor.sensor-data-cache.enabled=false} rolls the whole feature back without a rebuild.
 */
@ConfigurationProperties("wind-sensor.sensor-data-cache")
public class SensorDataCacheProperties {

    /** Base validity used when the floating window contributes nothing. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /** Default half-width of the band an entry's expiry is drawn from. */
    public static final Duration DEFAULT_FLOATING_WINDOW = Duration.ofMinutes(1);

    /** Base time-to-live of a cached sensor response. */
    private Duration ttl = DEFAULT_TTL;

    /** Half-width of the expiry band: each entry lives for {@code ttl ± window}. */
    private Duration floatingWindow = DEFAULT_FLOATING_WINDOW;

    /** Whether {@code /sensor-data} consults and writes the cache at all. */
    private boolean enabled = true;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getFloatingWindow() {
        return floatingWindow;
    }

    public void setFloatingWindow(Duration floatingWindow) {
        this.floatingWindow = floatingWindow;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
