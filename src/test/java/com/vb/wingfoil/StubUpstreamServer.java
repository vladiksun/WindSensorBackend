package com.vb.wingfoil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback upstream for the caching suites. Every behaviour is chosen by the sensor id in the request path, so one
 * provider configuration can produce a normal payload, an empty payload, an error status and a stalled response — and
 * each call is counted so a test can tell "answered from cache" from "fetched again" instead of inferring it.
 *
 * <p>Deliberately not MockServer: the caching suites must run without Docker, and a counter plus recorded request
 * headers is all they need.
 */
final class StubUpstreamServer implements AutoCloseable {

    /** Sensor id that answers 503. */
    static final String UNAVAILABLE_SENSOR_ID = "unavailable";

    /** Sensor id that answers 403 — what a provider that has blocked this host (Windguru does) replies with. */
    static final String FORBIDDEN_SENSOR_ID = "forbidden";

    /** Sensor id that answers a successful payload holding no readings. */
    static final String EMPTY_SENSOR_ID = "empty";

    /** Sensor id that accepts the connection and then stalls, so the configured response timeout is what fails it. */
    static final String STALLED_SENSOR_ID = "stalled";

    /** Sensor ids answered slowly enough for concurrent requests to pile up behind one call. */
    static final String SLOW_SENSOR_ID = "slow";

    private static final String WINDY_PAYLOAD = """
            {
              "status": "success",
              "response": {
                "info": {"id": "stub", "name": "Stub Station"},
                "data": [
                  {"wind_max": 7.5, "wind_avg": 5.2, "wind_min": 3.0, "wind_direction": 180.0, "timestamp": 1700000000},
                  {"wind_max": 8.0, "wind_avg": 6.0, "wind_min": 4.0, "wind_direction": 190.0, "timestamp": 1700003600}
                ]
              },
              "time": 1700000000
            }""";

    private static final String EMPTY_PAYLOAD = """
            {"status": "success", "response": {"info": {"id": "stub"}, "data": []}, "time": 1700000000}""";

    private static final String ERROR_PAYLOAD = "{\"status\": \"error\", \"error\": \"no data from provider\"}";

    /** Long enough that a one-second response timeout expires while this handler is still holding the call open. */
    private static final Duration STALLED_RESPONSE_DELAY = Duration.ofSeconds(4);

    /** Long enough that concurrent requests pile up behind one in-flight call, yet short for test runs. */
    private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(700);

    private final HttpServer server;

    /**
     * Handlers run on their own threads: without an executor the server dispatches exchanges one at a time, so a call
     * deliberately held open would delay every other test's upstream request and make unrelated cases time out.
     */
    private final ExecutorService upstreamHandlers;

    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    private final Map<String, Map<String, List<String>>> recordedHeaders = new ConcurrentHashMap<>();

    StubUpstreamServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstreamHandlers = Executors.newCachedThreadPool();
        server.createContext("/api/windy/", this::handle);
        server.createContext("/api/probe/", this::handle);
        server.setExecutor(upstreamHandlers);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String windyUrlTemplate() {
        return baseUrl() + "/api/windy/%s";
    }

    String probeUrlTemplate() {
        return baseUrl() + "/api/probe/%s";
    }

    /**
     * How long this server holds a stalled call open for. A client that timed out earlier left its pooled connection
     * abandoned, and that connection is only released once this delay ends — so a test that needs the next request to
     * reach upstream at all has to outlast it first.
     */
    Duration stalledResponseDelay() {
        return STALLED_RESPONSE_DELAY;
    }

    /** How many times this path was fetched since the last {@link #resetRecords()}. */
    int callsFor(String path) {
        var counter = callCounts.get(path);

        return counter == null ? 0 : counter.get();
    }

    /**
     * Whether this path reached {@code expected} fetches within {@code timeout}. A call the client abandoned through a
     * timeout can still be dispatched late — the server thread that was holding the previous call is only free once
     * its own delay ends — so the count that proves "the next identical request fetched again" is waited for instead
     * of read straight after the response.
     */
    boolean awaitCallsFor(String path, int expected, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (callsFor(path) >= expected) {
                return true;
            }

            sleepQuietly(Duration.ofMillis(50));
        }

        return callsFor(path) >= expected;
    }

    /** Request headers of the most recent call to {@code path}, keyed case-insensitively. */
    Map<String, List<String>> headersFor(String path) {
        return recordedHeaders.getOrDefault(path, Map.of());
    }

    void resetRecords() {
        callCounts.clear();
        recordedHeaders.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        upstreamHandlers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var sensorId = path.substring(path.lastIndexOf('/') + 1);

        callCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        recordedHeaders.put(path, recordHeaders(exchange));

        if (STALLED_SENSOR_ID.equals(sensorId)) {
            // The client gives up long before this finishes, so nothing is ever written here: a response that arrived
            // after the client had already abandoned the connection would linger in the pooled socket and could answer
            // a later request with the wrong body.
            sleepQuietly(STALLED_RESPONSE_DELAY);
            exchange.close();

            return;
        }

        int status;
        String body;
        switch (sensorId) {
            case UNAVAILABLE_SENSOR_ID -> {
                status = 503;
                body = ERROR_PAYLOAD;
            }
            case FORBIDDEN_SENSOR_ID -> {
                status = 403;
                body = ERROR_PAYLOAD;
            }
            case EMPTY_SENSOR_ID -> {
                status = 200;
                body = EMPTY_PAYLOAD;
            }
            default -> {
                // A slow upstream has to actually be slow, or the tests that depend on an in-flight call would race
                // against an answer that arrived too quickly.
                if (SLOW_SENSOR_ID.equals(sensorId)) {
                    sleepQuietly(SLOW_RESPONSE_DELAY);
                }
                status = 200;
                body = WINDY_PAYLOAD;
            }
        }

        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, List<String>> recordHeaders(HttpExchange exchange) {
        var recorded = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> recorded.put(name, List.copyOf(values)));

        return recorded;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
