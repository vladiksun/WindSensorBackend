package com.vb.wingfoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.vb.wingfoil.tiles.TileProxyException;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * What {@code GlobalExceptionHandler} renders for each kind of failure it can be handed: the status, and whether the
 * JSON body stays free of internal request details. The handler is the single place where an exception becomes a
 * response, so this is asserted directly on it rather than through an embedded server.
 */
class GlobalExceptionHandlerTest {

    /** The kind of text only logs may carry: host, path and query of the internal provider call. */
    private static final String INTERNAL_FETCH_URL = "http://127.0.0.1:8080/api/windy/15693?q=station_data";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void anApplicationExceptionIsRenderedWithTheStatusItCarriesAndOnlyItsSafeMessage() {
        var upstreamFailure = new IOException("Upstream call failed with status 403 for " + INTERNAL_FETCH_URL);
        var failure = new AppException(403, "Sensor data provider returned an error", upstreamFailure);

        var response = handler.handle(HttpRequest.create(HttpMethod.POST, "/sensor-data"), failure);

        assertEquals(HttpStatus.FORBIDDEN.getCode(), response.code(), "the provider's own status reaches the client");
        var message = response.getBody().orElseThrow().getMessage();
        assertEquals("Sensor data provider returned an error", message);
        assertFalse(message.contains("127.0.0.1"), "no provider host in the client body: " + message);
        assertFalse(message.contains("/api/windy/"), "no provider path in the client body: " + message);
        assertFalse(message.contains("station_data"), "no query string in the client body: " + message);
        assertSame(upstreamFailure, failure.getCause(), "the original failure stays available for the logs");
    }

    @Test
    void anApplicationExceptionWithoutANamedHttpStatusConstantStillCarriesThatCode() {
        var failure = new AppException(599, "Sensor data provider returned an error", new IOException("upstream 599"));

        // Micronaut stores the raw code, and only `code()` reads it back: `getStatus()` would insist on a named
        // constant and refuse 599.
        assertEquals(
                599,
                handler.handle(HttpRequest.create(HttpMethod.POST, "/sensor-data"), failure)
                        .code());
    }

    @Test
    void aTileProxyFailureStillRendersItsOwnStatusAndMessage() {
        var tileFailure = new TileProxyException(HttpStatus.BAD_GATEWAY, "Upstream tile call failed with status 503");

        var response = handler.handle(HttpRequest.create(HttpMethod.GET, "/tiles/1/2/3.png"), tileFailure);

        assertEquals(502, response.code(), "tile-proxy behaviour is unchanged");
        assertEquals(
                "Upstream tile call failed with status 503",
                response.getBody().orElseThrow().getMessage());
    }

    @Test
    void anyOtherFailureIsStillAnInternalServerErrorWithItsOwnMessage() {
        var validationFailure = new IllegalArgumentException("provider parameter must not be null");

        var response = handler.handle(HttpRequest.create(HttpMethod.POST, "/sensor-data"), validationFailure);

        assertEquals(500, response.code());
        assertEquals(
                "provider parameter must not be null",
                response.getBody().orElseThrow().getMessage());
    }
}
