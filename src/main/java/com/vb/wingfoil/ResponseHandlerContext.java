package com.vb.wingfoil;

import com.vb.wingfoil.provider.WindDataProvider;

/**
 * Context object to encapsulate parameters for handling HTTP responses when fetching sensor data.
 *
 * <p>The window and reading count arrive already resolved: {@link ProxyService} decides them before it calls the
 * provider, because they select which cache entry a response belongs to as well as how the payload is reduced, and
 * the response handler must therefore apply those same values instead of re-deriving them from its own defaults.
 */
public record ResponseHandlerContext(
        WindDataProvider<?> provider, String sensorId, String url, int readingWindow, int numberOfReadings) {}
