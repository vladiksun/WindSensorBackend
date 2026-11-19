package com.vb.wingfoil;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * An upstream that accepts the connection and then never answers — the behavior a stalled 1chip.ru endpoint
 * shows. The shared client has to end such a call within its configured bounds so {@code /sensor-data} fails
 * as an upstream error instead of holding a request (and its thread) open indefinitely.
 *
 * <p>The upstream here is a local socket that accepts and stays silent, because MockServer 5.15 offers no
 * response delay; the response timeout is shortened for this spec so the check takes seconds, not the
 * production thirty.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindSensorControllerUpstreamTimeoutTest implements TestPropertyProvider {

    private static final List<Socket> HELD_SOCKETS = new ArrayList<>();

    private ServerSocket stalledUpstream;

    @Override
    public @NonNull Map<String, String> getProperties() {
        try {
            stalledUpstream = new ServerSocket(0);
        } catch (IOException e) {
            throw new IllegalStateException("could not open the stalled upstream socket", e);
        }

        Thread.ofVirtual().name("stalled-upstream").start(() -> {
            while (!stalledUpstream.isClosed()) {
                try {
                    HELD_SOCKETS.add(stalledUpstream.accept()); // read nothing, reply nothing
                } catch (IOException e) {
                    return;
                }
            }
        });

        return Map.of(
                "wind-sensor.wind-providers.onechip.url",
                "http://127.0.0.1:" + stalledUpstream.getLocalPort() + "/windt.php?id=%s",
                "wind-sensor.upstream-response-timeout-seconds",
                "2",
                "spec.name",
                "WindSensorControllerUpstreamTimeoutTest");
    }

    @AfterAll
    void closeStalledUpstream() throws IOException {
        for (var socket : HELD_SOCKETS) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        stalledUpstream.close();
    }

    @Test
    void stalledUpstreamFailsAsAnUpstreamErrorInsteadOfHanging(RequestSpecification spec) {
        var started = Instant.now();

        var response = spec.contentType(ContentType.JSON).body("""
                        {
                          "readingWindow": 3600,
                          "numberOfReadings": 5,
                          "sensor": {
                            "id": "0264",
                            "provider": "onechip"
                          }
                        }
                        """).when().post("/sensor-data");

        var elapsed = Duration.between(started, Instant.now());

        // An upstream error, not the compatible "no data" payload: the client must not pretend a stalled
        // endpoint simply had nothing to say. No upstream status was ever received, so this is a gateway failure —
        // 502 — and the body says only that the provider is unavailable.
        response.then().statusCode(502);
        var message = response.jsonPath().getString("message");
        assertNotNull(message, "the failure has to name a cause");
        assertFalse(message.contains("127.0.0.1"), "no provider host in the client body: " + message);
        assertFalse(message.contains("windt.php"), "no provider path in the client body: " + message);
        assertTrue(elapsed.toSeconds() < 20, "the call must end within its bounds, not hang: " + elapsed);

        System.out.println("UPSTREAM TIMEOUT probe: status=" + response.statusCode() + " message=" + message
                + " elapsed=" + elapsed.toMillis() + "ms");
    }
}
