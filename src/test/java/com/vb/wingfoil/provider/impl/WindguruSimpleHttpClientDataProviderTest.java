package com.vb.wingfoil.provider.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vb.wingfoil.AppException;
import com.vb.wingfoil.SensorDataDTO;
import com.vb.wingfoil.WindSensorConfig;
import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import com.vb.wingfoil.response.windguru.WindguruTokenResponse;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the Windguru provider: call-URL shape, the mandatory {@code Referer}, unzipping the
 * columnar payload into metre-per-second speeds, and Windguru's gap/no-data quirks.
 * <p>
 * The provider and the serde {@code ObjectMapper} come from the application context so the wired bean is
 * exercised against the real {@code application.yml} template. Fixture: a trimmed live capture of station
 * 15693 (Dahab – Assalah) in the knots Windguru publishes, including its extra columns and a published gap.
 */
@MicronautTest
class WindguruSimpleHttpClientDataProviderTest {

    private static final String STATION_ID = "15693";

    /** Row values of the fixture, oldest-first; index 3 is the row published with a null wind_avg. */
    private static final long GAP_TIMESTAMP = 1793786220L;

    private static final Pattern UTC_RANGE = Pattern.compile("&from=([0-9T:.Z-]+)&to=([0-9T:.Z-]+)$");

    private final WindguruSimpleHttpClientDataProvider provider;
    private final WindSensorConfig windSensorConfig;
    private final ObjectMapper objectMapper;

    private WindguruSimpleHttpClientDataProviderTest(
            WindguruSimpleHttpClientDataProvider provider,
            WindSensorConfig windSensorConfig,
            ObjectMapper objectMapper) {
        this.provider = provider;
        this.windSensorConfig = windSensorConfig;
        this.objectMapper = objectMapper;
    }

    private static String readFixture() throws IOException {
        var stream = WindguruSimpleHttpClientDataProviderTest.class.getResourceAsStream("/windguru/station-data.json");
        assertNotNull(stream, "missing windguru fixture");
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private List<SensorDataDTO> readings(String body, int window, int count) {
        return provider.extractTimedReadings(STATION_ID, body, window, count).get();
    }

    @Test
    void getCallUrlContainsStationIdAndUtcRange() {
        var url = provider.getCallUrl(STATION_ID);

        assertTrue(url.startsWith("https://www.windguru.cz/int/iapi.php?"), url);
        assertTrue(url.contains("q=station_data"), url);
        assertTrue(url.contains("id_station=" + STATION_ID), url);
        assertTrue(url.contains("avg_minutes=1"), url);
        assertFalse(url.contains("%s"), "the station-id placeholder must be substituted: " + url);

        var matcher = UTC_RANGE.matcher(url);
        assertTrue(matcher.find(), "call url must end with an ISO-UTC from/to range: " + url);

        var from = Instant.parse(matcher.group(1));
        var to = Instant.parse(matcher.group(2));
        assertTrue(from.isBefore(to), "from must precede to: " + url);
        assertEquals(
                WindguruSimpleHttpClientDataProvider.LOOK_BACK_SECONDS, to.getEpochSecond() - from.getEpochSecond());
    }

    /**
     * Both headers Windguru requires of a {@code station_data} call: the station page as the referring page, and the
     * access token this provider minted for it. The mint is one plain JSON GET — no cookie jar copied from a browser
     * session, and no second action asked for along the way.
     */
    @Test
    void requestHeadersCarryRefererAndMintedToken() throws Exception {
        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
            assertEquals(
                    Map.of("Referer", "https://www.windguru.cz/station/" + STATION_ID, "X-WG-Token", "minted-token-1"),
                    tokenProvider.requestHeaders(STATION_ID));

            assertEquals(List.of("q=token_refresh"), upstream.tokenQueries());
            var sent = upstream.lastTokenRequestHeaders();
            assertEquals(List.of("application/json"), sent.get("Accept"));
            assertFalse(sent.containsKey("Cookie"), "the provider carries no cookie session: " + sent.keySet());
        }
    }

    @Test
    void providersWithoutDeclaredHeadersStayOnTheDefault() {
        // The hook is a default method: a provider that does not override it declares nothing, so the
        // shared request builder adds no headers for it. (1chip overrides it; windy does not.)
        var windy = new WindyDataProvider(new WindDataProviderConfig(WindyDataProvider.NAME), null);

        assertTrue(windy.requestHeaders("12345").isEmpty());
    }

    @Test
    void parsesColumnarPayloadIntoAscendingReadings() throws Exception {
        var result = readings(readFixture(), 86400, 100);

        // 8 published rows, one of them a gap -> 7 complete readings.
        assertEquals(7, result.size());

        for (int i = 1; i < result.size(); i++) {
            assertTrue(
                    result.get(i - 1).timestamp() < result.get(i).timestamp(), "readings must be strictly ascending");
        }
    }

    @Test
    void convertsPublishedKnotsToMetresPerSecond() throws Exception {
        var oldest = readings(readFixture(), 86400, 100).getFirst();

        // The fixture keeps the published knots (20.2 / 23.1 / 26.0); only the speeds come back converted.
        assertEquals(1793785260L, oldest.timestamp());
        assertEquals(10.39f, oldest.windMin(), 0.001f);
        assertEquals(11.88f, oldest.windAvg(), 0.001f);
        assertEquals(13.38f, oldest.windMax(), 0.001f);
        assertEquals(10.0f, oldest.windDirection(), 0.001f);
    }

    @Test
    void knotsAreConvertedWithTheExactNauticalMileFactor() {
        assertEquals(0.51f, WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(1f), 0.001f);
        assertEquals(1.85f, WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(3.6f), 0.001f);
    }

    @Test
    void convertedSpeedsAreRoundedHalfUpToTwoDecimals() {
        assertEquals(13.38f, WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(26f), 0.001f);
        assertEquals(12.50f, WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(24.3f), 0.001f);
    }

    @Test
    void zeroSpeedStaysZeroAndConversionKeepsSpeedOrdering() {
        assertEquals(0f, WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(0f), 0.001f);

        var min = WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(20.2f);
        var avg = WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(23.1f);
        var max = WindguruSimpleHttpClientDataProvider.knotsToMetresPerSecond(26f);

        assertTrue(
                min <= avg && avg <= max,
                "conversion must not reorder a published min/avg/max: %s/%s/%s".formatted(min, avg, max));
    }

    @Test
    void skipsRowsWithMissingValues() throws Exception {
        var result = readings(readFixture(), 86400, 100);

        // The fixture keeps a null wind_avg at index 3 (the shape Windguru publishes for a gap).
        assertTrue(
                result.stream().noneMatch(r -> r.timestamp() == GAP_TIMESTAMP),
                "a row published with a missing value must not become a reading: " + result);
        assertEquals(7, result.size());
    }

    @Test
    void latestOnlyRequestReturnsTheNewestReading() throws Exception {
        var result = readings(readFixture(), 0, 0);

        assertEquals(1, result.size());
        var latest = result.getFirst();
        assertEquals(1793787360L, latest.timestamp());
        // Newest published row 20.8 / 24.3 / 28.7 kn -> 10.70 / 12.50 / 14.76 m/s.
        assertEquals(10.70f, latest.windMin(), 0.001f);
        assertEquals(12.50f, latest.windAvg(), 0.001f);
        assertEquals(14.76f, latest.windMax(), 0.001f);
        assertEquals(37.0f, latest.windDirection(), 0.001f);
    }

    @Test
    void windowedRequestHonoursReadingWindowAndCount() throws Exception {
        var result = readings(readFixture(), 3600, 5);

        // Every complete reading lies within 2100 s of the newest one, so the window keeps all of them
        // and the shared reducer thins them to 5 while preserving first and last.
        assertEquals(5, result.size());
        assertEquals(1793785260L, result.getFirst().timestamp());
        assertEquals(1793787360L, result.getLast().timestamp());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).timestamp() < result.get(i).timestamp(), "readings must be ascending");
        }
    }

    @Test
    void speedsAreConvertedOnceWhateverTheReadingWindowIs() throws Exception {
        var latestOnly = readings(readFixture(), 0, 0).getFirst();
        var windowedLatest = readings(readFixture(), 86400, 100).getLast();

        // Same newest row through both paths (getLastReading vs the shared reducer): one conversion each.
        assertEquals(windowedLatest.timestamp(), latestOnly.timestamp());
        assertEquals(12.50f, latestOnly.windAvg(), 0.001f);
        assertEquals(12.50f, windowedLatest.windAvg(), 0.001f);
        assertNotEquals(6.43f, latestOnly.windAvg(), 0.001f);
        assertNotEquals(6.43f, windowedLatest.windAvg(), 0.001f);
    }

    @Test
    void emptyArrayBodyReturnsSingleEmptyReading() {
        assertEquals(List.of(SensorDataDTO.empty()), readings("[]", 3600, 5));
        assertEquals(List.of(SensorDataDTO.empty()), readings("\n  [ ]  \n", 3600, 5));
    }

    @Test
    void blankBodyReturnsSingleEmptyReading() {
        assertEquals(List.of(SensorDataDTO.empty()), readings("", 3600, 5));
        assertEquals(List.of(SensorDataDTO.empty()), readings(null, 3600, 5));
    }

    @Test
    void payloadWithoutUsableColumnsReturnsSingleEmptyReading() {
        // A truncated/failed payload must not produce zero-speed readings.
        var result = readings("{\"unixtime\":[1793785260],\"wind_avg\":[null]}", 3600, 5);

        assertEquals(List.of(SensorDataDTO.empty()), result);
    }

    @Test
    void unparseablePayloadIsRecoveredAsNoData() {
        var result = readings("{ this is not json", 3600, 5);

        assertEquals(List.of(SensorDataDTO.empty()), result);
    }

    // ========================= token minting, caching and failure =========================

    /** Every token test runs on this instant: expiry is decided by a clock the test owns, not by wall time. */
    private static final Instant TOKEN_START = Instant.parse("2026-11-19T12:00:00Z");

    /** The header a station-data call must carry next to its {@code Referer}. */
    private static final String TOKEN_HEADER = "X-WG-Token";

    /** What the client is answered with when no usable token could be obtained; the cause stays in the logs. */
    private static final String AUTHENTICATION_UNAVAILABLE = "Windguru authentication unavailable";

    /**
     * The live payload shape: the reserved top-level {@code return}, a {@code message}, and a {@code wgk} block sitting
     * beside the token inside {@code data}. Only {@code data.token} and its advertised {@code data.ttl} are read, so
     * none of the rest has to be mapped and none of it can break token acquisition.
     */
    @Test
    void tokenPayloadKeepsOnlyTheTokenAndItsTtl() throws Exception {
        var payload = "{\"return\": 0, \"message\": {\"status\": \"ok\"}, \"wgk\": {\"key\": \"rotates\"},"
                + " \"data\": {\"token\": \"abc123\", \"ttl\": 1800}}";

        var response = objectMapper.readValue(payload, WindguruTokenResponse.class);

        assertEquals("abc123", response.data().token());
        assertEquals(1800, response.data().ttl());
    }

    /**
     * The token action lives on the script the configured station-data URL already names: everything up to the first
     * {@code ?}, with the station-id placeholder dropped and exactly {@code q=token_refresh} asked for.
     */
    @Test
    void tokenActionUrlIsDerivedFromTheConfiguredStationDataUrl() {
        assertEquals(
                "https://www.windguru.cz/int/iapi.php?q=token_refresh",
                WindguruSimpleHttpClientDataProvider.tokenUrlFrom(provider.getUrl()));

        assertEquals(
                "https://www.windguru.cz/int/iapi.php?q=token_refresh",
                WindguruSimpleHttpClientDataProvider.tokenUrlFrom(
                        "https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1&graph_info=1"));

        // A placeholder sitting in the path rather than in the query is dropped just the same.
        assertEquals(
                "http://127.0.0.1:8081/int/iapi.php?q=token_refresh",
                WindguruSimpleHttpClientDataProvider.tokenUrlFrom(
                        "http://127.0.0.1:8081/int/iapi.php%s?q=station_data"));
    }

    /** The advertised TTL is shortened by its margin, capped, and floored — never trusted as advertised. */
    @Test
    void mintedTokenLifetimeIsShortenedCappedAndFloored() {
        assertEquals(900, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(1800));
        assertEquals(540, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(600));
        assertEquals(1, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(61));
        assertEquals(1, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(30));

        // An absent or non-positive advertised TTL falls back to the cap rather than to an unbounded lifetime.
        assertEquals(900, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(null));
        assertEquals(900, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(0));
        assertEquals(900, WindguruSimpleHttpClientDataProvider.effectiveLifetimeSeconds(-5));
    }

    /** A minted token is reused while it is valid and replaced by exactly one fresh mint once it is not. */
    @Test
    void mintedTokenIsReusedWhileValidAndRefreshedOnceItExpires() throws Exception {
        var clock = new MutableClock(TOKEN_START);

        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, clock)) {
            assertEquals(
                    "minted-token-1", tokenProvider.requestHeaders(STATION_ID).get(TOKEN_HEADER));

            // The advertised half hour is trusted for at most 900 s, so a call well inside that window reuses it.
            clock.advance(Duration.ofSeconds(600));
            assertEquals(
                    "minted-token-1", tokenProvider.requestHeaders(STATION_ID).get(TOKEN_HEADER));
            assertEquals(1, upstream.tokenRequests());

            // Past the borrowed validity the token is refreshed rather than sent again, and one refresh suffices.
            clock.advance(Duration.ofSeconds(301));
            assertEquals(
                    "minted-token-2", tokenProvider.requestHeaders(STATION_ID).get(TOKEN_HEADER));
            assertEquals(2, upstream.tokenRequests());
        }
    }

    /**
     * The mint is a JSON request of its own with no session attached to it, and it names the upstream site as its
     * referring page — see docs/windguru_api_rate_limit.md. That last part is load-bearing: live probes on 2026-11-19
     * got {@code 401} from {@code q=token_refresh} for this exact request with the Referer removed.
     */
    @Test
    void aTokenMintIsAskedForJsonAndCarriesNoCookieSession() throws Exception {
        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {

            tokenProvider.requestHeaders(STATION_ID);

            var headers = upstream.lastTokenRequestHeaders();
            assertEquals(List.of("application/json"), headers.get("Accept"), "the token action is asked for JSON");
            assertTrue(headers.keySet().stream().noneMatch("Cookie"::equalsIgnoreCase), "no cookie session is sent");
            assertEquals(
                    List.of("http://127.0.0.1:" + upstream.port() + "/"),
                    headers.get("Referer"),
                    "the mint refers to a page of the site it is asked for its token from");
        }
    }

    /** The Referer the token action is told about is the origin of the configured endpoint, not a hardcoded host. */
    @Test
    void tokenRefererIsTheSiteRootOfTheConfiguredTokenEndpoint() throws Exception {
        // The bean assembled from application.yml points at the live site, so its mint names that site's root —
        // derived from configuration, and without anything being fetched here.
        assertEquals("https://www.windguru.cz/", provider.tokenReferer());

        try (var upstream = new TokenEndpointStub(1800);
                var stubBacked = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
            assertEquals(
                    "http://127.0.0.1:" + upstream.port() + "/",
                    stubBacked.tokenReferer(),
                    "a provider aimed at a loopback stub refers to that stub rather than to windguru.cz");
        }
    }

    /** A payload that advertises no TTL is still a usable token; its lifetime then falls back to the cap. */
    @Test
    void tokenWithoutAdvertisedTtlIsStillAccepted() throws Exception {
        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
            upstream.respondWith(200, "{\"return\": 0, \"data\": {\"token\": \"no-ttl-token\"}}");

            assertEquals(
                    "no-ttl-token", tokenProvider.requestHeaders(STATION_ID).get(TOKEN_HEADER));
            assertEquals(1, upstream.tokenRequests());
        }
    }

    /** Concurrent requests share one mint: the lock and the second check inside it yield a single replacement. */
    @Test
    void concurrentHeaderPreparationMintsOneTokenForOneExpiryWindow() throws Exception {
        var executor = Executors.newFixedThreadPool(8);

        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
            var futures = IntStream.range(0, 8)
                    .mapToObj(ignored ->
                            CompletableFuture.supplyAsync(() -> tokenProvider.requestHeaders(STATION_ID), executor))
                    .toList();

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "header preparation must not deadlock");

            var tokens = futures.stream()
                    .map(preparedHeaders -> preparedHeaders.join().get(TOKEN_HEADER))
                    .distinct()
                    .toList();

            assertEquals(List.of("minted-token-1"), tokens);
            assertEquals(1, upstream.tokenRequests());
        } finally {
            executor.shutdownNow();
        }
    }

    /** A rejected mint is a 502 with client-safe text; it is not cached, so the next call attempts again. */
    @Test
    void rejectedTokenMintFailsAsClientSafeUpstreamAuthenticationError() throws Exception {
        try (var upstream = new TokenEndpointStub(1800);
                var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
            upstream.respondWith(403, "{\"return\": 403, \"message\": {\"status\": \"forbidden\"}}");

            var failure = assertThrows(AppException.class, () -> tokenProvider.requestHeaders(STATION_ID));
            assertEquals(502, failure.statusCode());
            assertEquals(AUTHENTICATION_UNAVAILABLE, failure.getMessage());
            assertTrue(
                    failure.getCause() instanceof IOException,
                    "the transport cause stays in the chain: " + failure.getCause());

            // Nothing is cached as "no token", so a later request gets its own attempt rather than a stored failure.
            var secondFailure = assertThrows(AppException.class, () -> tokenProvider.requestHeaders(STATION_ID));
            assertEquals(502, secondFailure.statusCode());
            assertEquals(2, upstream.tokenRequests(), "a failed mint is retried, but never endlessly");
        }
    }

    /** A 2xx answer without a usable token — absent, blank, or unparseable — fails exactly the same way. */
    @Test
    void tokenPayloadWithoutUsableTokenFailsAsClientSafeUpstreamAuthenticationError() throws Exception {
        var bodies = List.of(
                "{\"return\": 0, \"message\": {\"status\": \"ok\"}}",
                "{\"return\": 0, \"data\": {\"ttl\": 1800}}",
                "{\"return\": 0, \"data\": {\"token\": \"   \", \"ttl\": 1800}}",
                "{ this is not json");

        for (String body : bodies) {
            try (var upstream = new TokenEndpointStub(1800);
                    var tokenProvider = providerBackedBy(upstream, Clock.fixed(TOKEN_START, ZoneOffset.UTC))) {
                upstream.respondWith(200, body);

                var failure = assertThrows(AppException.class, () -> tokenProvider.requestHeaders(STATION_ID), body);

                assertEquals(502, failure.statusCode(), body);
                assertEquals(AUTHENTICATION_UNAVAILABLE, failure.getMessage(), body);
            }
        }
    }

    /** A provider configured against {@code upstream}: token endpoint, clock and token client supplied, not derived. */
    private WindguruSimpleHttpClientDataProvider providerBackedBy(TokenEndpointStub upstream, Clock clock) {
        var config = new WindDataProviderConfig(WindguruSimpleHttpClientDataProvider.NAME);
        config.setUrl(upstream.stationDataUrlTemplate());

        return new WindguruSimpleHttpClientDataProvider(
                config,
                objectMapper,
                WindguruSimpleHttpClientDataProvider.tokenUrlFrom(config.getUrl()),
                clock,
                WindguruSimpleHttpClientDataProvider.buildTokenClient(windSensorConfig));
    }

    /** A clock the test moves forward, so token expiry is a fact under test rather than an accident of timing. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Loopback stand-in for Windguru's {@code q=token_refresh} action. Every successful mint carries a distinct token
     * value, which is what makes "reused" and "refreshed" observable rather than inferred.
     */
    private static final class TokenEndpointStub implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger mints = new AtomicInteger();
        private final List<String> tokenQueries = new CopyOnWriteArrayList<>();
        private final List<Map<String, List<String>>> tokenRequestHeaders = new CopyOnWriteArrayList<>();
        private final int advertisedTtlSeconds;

        private int status = 200;
        private String body;

        TokenEndpointStub(int advertisedTtlSeconds) throws IOException {
            this.advertisedTtlSeconds = advertisedTtlSeconds;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/int/", this::handle);
            this.server.start();
        }

        /** The station-data template to configure for this stub, in the shape {@code application.yml} uses. */
        String stationDataUrlTemplate() {
            return "http://127.0.0.1:" + port() + "/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1";
        }

        int port() {
            return server.getAddress().getPort();
        }

        int tokenRequests() {
            return mints.get();
        }

        List<String> tokenQueries() {
            return List.copyOf(tokenQueries);
        }

        /** Request headers of the most recent token call, keyed case-insensitively. */
        Map<String, List<String>> lastTokenRequestHeaders() {
            return tokenRequestHeaders.getLast();
        }

        void respondWith(int status, String body) {
            this.status = status;
            this.body = body;
        }

        private void handle(HttpExchange exchange) throws IOException {
            var mint = mints.incrementAndGet();
            tokenQueries.add(exchange.getRequestURI().getQuery());

            var recorded = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> recorded.put(name, List.copyOf(values)));
            tokenRequestHeaders.add(recorded);

            var payload = body != null ? body : successfulMint(mint);
            var bytes = payload.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /** The live envelope, with the token rotating per mint so a reused token and a fresh one are distinguishable. */
        private String successfulMint(int mint) {
            return "{\"return\": 0, \"message\": {\"status\": \"ok\", \"debug\": \"ok\"},"
                    + " \"data\": {\"token\": \"minted-token-" + mint + "\", \"ttl\": " + advertisedTtlSeconds + "}}";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
