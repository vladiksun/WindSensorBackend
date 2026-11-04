package com.vb.wingfoil;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

import com.vb.wingfoil.WindSensorConfig.WindDataProviderConfig;
import com.vb.wingfoil.provider.impl.BaseWindyDataProvider;
import com.vb.wingfoil.response.windy.WindyMeasurement;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vavr.control.Try;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Header;
import org.mockserver.model.StringBody;
import org.mockserver.verify.VerificationTimes;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class WindSensorControllerMockServerTest implements TestPropertyProvider {

    @Container
    static MockServerContainer mockServerContainer =
            new MockServerContainer(DockerImageName.parse("mockserver/mockserver:5.15.0"));

    static MockServerClient mockServerClient;

    @Override
    public @NonNull Map<String, String> getProperties() {
        mockServerContainer.start();
        mockServerClient = new MockServerClient(mockServerContainer.getHost(), mockServerContainer.getServerPort());

        return Map.of(
                "wind-sensor.wind-providers.windy.url",
                mockServerContainer.getEndpoint() + "/api/windy/%s",
                "wind-sensor.wind-providers.neduet.url",
                mockServerContainer.getEndpoint() + "/api/neduet",
                "wind-sensor.spots-data-url",
                mockServerContainer.getEndpoint() + "/api/spots",
                "wind-sensor.spots-test-data-url",
                mockServerContainer.getEndpoint() + "/api/spots-test",
                "wind-sensor.spots-dahab-url",
                mockServerContainer.getEndpoint() + "/api/spots-dahab",
                "wind-sensor.spots-data-media-type",
                "application/json",
                "wind-sensor.wind-providers.windguru.url",
                mockServerContainer.getEndpoint() + "/api/windguru/%s?q=station_data",
                "wind-sensor.wind-providers.probe.url",
                mockServerContainer.getEndpoint() + "/api/probe/%s",
                "spec.name",
                "WindSensorControllerMockServerTest");
    }

    @BeforeEach
    void setUp() {
        mockServerClient.reset();
    }

    // ========================================================================
    // POST /sensor-data tests
    // ========================================================================

    @Test
    void shouldGetSensorDataFromWindyProvider(RequestSpecification spec) {
        String sensorId = "12345";

        mockServerClient
                .when(request().withMethod("GET").withPath("/api/windy/" + sensorId))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                {
                                  "status": "success",
                                  "response": {
                                    "info": {
                                      "id": "12345",
                                      "name": "Test Station"
                                    },
                                    "data": [
                                      {
                                        "wind_max": 7.5,
                                        "wind_avg": 5.2,
                                        "wind_min": 3.0,
                                        "wind_direction": 180.0,
                                        "timestamp": 1700000000,
                                        "pressure": 1013.0,
                                        "temperature": 20.0,
                                        "humidity": 60.0
                                      },
                                      {
                                        "wind_max": 8.0,
                                        "wind_avg": 6.0,
                                        "wind_min": 4.0,
                                        "wind_direction": 190.0,
                                        "timestamp": 1700003600,
                                        "pressure": 1012.0,
                                        "temperature": 21.0,
                                        "humidity": 55.0
                                      }
                                    ]
                                  },
                                  "time": 1700000000
                                }
                                """)));

        String requestBody = """
                {
                  "readingWindow": 7200,
                  "numberOfReadings": 2,
                  "sensor": {
                    "id": "%s",
                    "provider": "windy",
                    "label": "Test Sensor"
                  }
                }
                """.formatted(sensorId);

        spec.contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].windMax", is(7.5f))
                .body("[0].windAvg", is(5.2f))
                .body("[0].windMin", is(3.0f))
                .body("[0].windDirection", is(180.0f))
                .body("[0].timestamp", is(1700000000))
                .body("[1].windMax", is(8.0f))
                .body("[1].windAvg", is(6.0f));

        verifyMockServerRequest("GET", "/api/windy/" + sensorId, 1);
    }

    @Test
    void shouldGetSensorDataFromNeduetProvider(RequestSpecification spec) {
        String sensorId = "neduet-sensor-1";

        mockServerClient
                .when(request().withMethod("GET").withPath("/api/neduet"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "name": "Neduet Station 1",
                                    "id": "neduet-sensor-1",
                                    "data": [
                                      {
                                        "max": 9.0,
                                        "avr": 6.5,
                                        "min": 4.0,
                                        "dir": 270.0,
                                        "timestamp": 1700000000
                                      },
                                      {
                                        "max": 10.0,
                                        "avr": 7.0,
                                        "min": 5.0,
                                        "dir": 280.0,
                                        "timestamp": 1700003600
                                      }
                                    ]
                                  },
                                  {
                                    "name": "Neduet Station 2",
                                    "id": "neduet-sensor-2",
                                    "data": []
                                  }
                                ]
                                """)));

        String requestBody = """
                {
                  "readingWindow": 7200,
                  "numberOfReadings": 2,
                  "sensor": {
                    "id": "%s",
                    "provider": "neduet",
                    "label": "Neduet Test Sensor"
                  }
                }
                """.formatted(sensorId);

        spec.contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].windMax", is(9.0f))
                .body("[0].windAvg", is(6.5f))
                .body("[0].windMin", is(4.0f))
                .body("[0].windDirection", is(270.0f))
                .body("[0].timestamp", is(1700000000))
                .body("[1].windMax", is(10.0f));

        verifyMockServerRequest("GET", "/api/neduet", 1);
    }

    @Test
    void shouldReturnEmptySensorDataWhenWindyReturnsEmptyResponse(RequestSpecification spec) {
        String sensorId = "empty-sensor";

        mockServerClient
                .when(request().withMethod("GET").withPath("/api/windy/" + sensorId))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(new StringBody("")));

        String requestBody = """
                {
                  "sensor": {
                    "id": "%s",
                    "provider": "windy"
                  }
                }
                """.formatted(sensorId);

        spec.contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f))
                .body("[0].windAvg", is(0.0f))
                .body("[0].timestamp", is(0));

        verifyMockServerRequest("GET", "/api/windy/" + sensorId, 1);
    }

    @Test
    void shouldReturnEmptySensorDataWhenNeduetReturnsEmptyResponse(RequestSpecification spec) {
        String sensorId = "neduet-empty";

        mockServerClient
                .when(request().withMethod("GET").withPath("/api/neduet"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "name": "Not Found Station",
                                    "id": "other-sensor",
                                    "data": []
                                  }
                                ]
                                """)));

        String requestBody = """
                {
                  "sensor": {
                    "id": "%s",
                    "provider": "neduet"
                  }
                }
                """.formatted(sensorId);

        spec.contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f))
                .body("[0].windAvg", is(0.0f))
                .body("[0].timestamp", is(0));

        verifyMockServerRequest("GET", "/api/neduet", 1);
    }

    @Test
    void shouldReturnEmptySensorDataWhenWindyReturnsEmptyDataArray(RequestSpecification spec) {
        String sensorId = "no-data-sensor";

        mockServerClient
                .when(request().withMethod("GET").withPath("/api/windy/" + sensorId))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                {
                                  "status": "success",
                                  "response": {
                                    "info": {
                                      "id": "no-data-sensor",
                                      "name": "No Data Station"
                                    },
                                    "data": []
                                  },
                                  "time": 1700000000
                                }
                                """)));

        String requestBody = """
                {
                  "sensor": {
                    "id": "%s",
                    "provider": "windy"
                  }
                }
                """.formatted(sensorId);

        spec.contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f))
                .body("[0].windAvg", is(0.0f))
                .body("[0].timestamp", is(0));

        verifyMockServerRequest("GET", "/api/windy/" + sensorId, 1);
    }

    // ========================================================================
    // GET /spots-data tests
    // ========================================================================

    @Test
    void shouldGetSpotsDataProductionMode(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/spots"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "location": "Anapa",
                                    "readingWindow": 3600,
                                    "numberOfReadings": 5,
                                    "sensors": [
                                      {
                                        "id": "windy-1",
                                        "provider": "windy",
                                        "label": "Anapa Beach",
                                        "tiles": [
                                          {
                                            "tileLabel": "Anapa North",
                                            "latitude": 45.3798,
                                            "longitude": 36.0820
                                          },
                                          {
                                            "tileLabel": "Anapa South",
                                            "latitude": 45.3600,
                                            "longitude": 36.0900
                                          }
                                        ]
                                      }
                                    ]
                                  },
                                  {
                                    "location": "Sochi",
                                    "readingWindow": 7200,
                                    "numberOfReadings": 10,
                                    "sensors": [
                                      {
                                        "id": "windy-2",
                                        "provider": "windy",
                                        "label": "Sochi Port",
                                        "tiles": [
                                          {
                                            "tileLabel": "Sochi Port West",
                                            "latitude": 43.5855,
                                            "longitude": 39.7231
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                ]
                                """)));

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].location", is("Anapa"))
                .body("[0].readingWindow", is(3600))
                .body("[0].numberOfReadings", is(5))
                .body("[0].sensors.size()", is(1))
                .body("[0].sensors[0].id", is("windy-1"))
                .body("[0].sensors[0].tiles.size()", is(2))
                .body("[0].sensors[0].tiles[0].tileLabel", is("Anapa North"))
                .body("[0].sensors[0].tiles[0].latitude", is(45.3798f))
                .body("[0].sensors[0].tiles[0].longitude", is(36.0820f))
                .body("[0].sensors[0].tiles[1].tileLabel", is("Anapa South"))
                .body("[0].sensors[0].tiles[1].latitude", is(45.3600f))
                .body("[0].sensors[0].tiles[1].longitude", is(36.0900f))
                .body("[1].location", is("Sochi"))
                .body("[1].readingWindow", is(7200))
                .body("[1].sensors[0].tiles.size()", is(1))
                .body("[1].sensors[0].tiles[0].tileLabel", is("Sochi Port West"))
                .body("[1].sensors[0].tiles[0].latitude", is(43.5855f))
                .body("[1].sensors[0].tiles[0].longitude", is(39.7231f));

        verifyMockServerRequest("GET", "/api/spots", 1);
    }

    @Test
    void shouldGetSpotsDataDebugMode(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/spots-test"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "location": "Test Spot",
                                    "readingWindow": 1800,
                                    "numberOfReadings": 3,
                                    "sensors": [
                                      {
                                        "id": "test-1",
                                        "provider": "windy",
                                        "label": "Test Sensor",
                                        "tiles": [
                                          {
                                            "tileLabel": "Test Spot Tile",
                                            "latitude": 27.2566,
                                            "longitude": 33.8129
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                ]
                                """)));

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data?isDebug=true")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].location", is("Test Spot"))
                .body("[0].readingWindow", is(1800))
                .body("[0].numberOfReadings", is(3))
                .body("[0].sensors[0].id", is("test-1"))
                .body("[0].sensors[0].tiles.size()", is(1))
                .body("[0].sensors[0].tiles[0].tileLabel", is("Test Spot Tile"))
                .body("[0].sensors[0].tiles[0].latitude", is(27.2566f))
                .body("[0].sensors[0].tiles[0].longitude", is(33.8129f));

        verifyMockServerRequest("GET", "/api/spots-test", 1);
    }

    @Test
    void shouldGetSpotsDataDahab(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/spots-dahab"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "location": "Dahab",
                                    "readingWindow": 5400,
                                    "numberOfReadings": 8,
                                    "sensors": [
                                      {
                                        "id": "dahab-1",
                                        "provider": "neduet",
                                        "label": "Dahab Blue Hole",
                                        "tiles": [
                                          {
                                            "tileLabel": "Blue Hole North",
                                            "latitude": 28.5369,
                                            "longitude": 34.5710
                                          }
                                        ]
                                      },
                                      {
                                        "id": "dahab-2",
                                        "provider": "windy",
                                        "label": "Dahab Lagoon",
                                        "tiles": [
                                          {
                                            "tileLabel": "Lagoon West",
                                            "latitude": 28.5280,
                                            "longitude": 34.5560
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                ]
                                """)));

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data-dahab")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].location", is("Dahab"))
                .body("[0].readingWindow", is(5400))
                .body("[0].numberOfReadings", is(8))
                .body("[0].sensors.size()", is(2))
                .body("[0].sensors[0].id", is("dahab-1"))
                .body("[0].sensors[0].tiles.size()", is(1))
                .body("[0].sensors[0].tiles[0].tileLabel", is("Blue Hole North"))
                .body("[0].sensors[0].tiles[0].latitude", is(28.5369f))
                .body("[0].sensors[0].tiles[0].longitude", is(34.5710f))
                .body("[0].sensors[1].id", is("dahab-2"))
                .body("[0].sensors[1].tiles.size()", is(1))
                .body("[0].sensors[1].tiles[0].tileLabel", is("Lagoon West"))
                .body("[0].sensors[1].tiles[0].latitude", is(28.5280f))
                .body("[0].sensors[1].tiles[0].longitude", is(34.5560f));

        verifyMockServerRequest("GET", "/api/spots-dahab", 1);
    }

    @Test
    void shouldReturnEmptyListWhenSpotsDataReturnsEmptyArray(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/spots"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("[]")));

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data")
                .then()
                .statusCode(200)
                .body("size()", is(0));

        verifyMockServerRequest("GET", "/api/spots", 1);
    }

    @Test
    void shouldHandleSensorsWithoutTiles(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/spots"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                [
                                  {
                                    "location": "Legacy Spot",
                                    "readingWindow": 3600,
                                    "numberOfReadings": 5,
                                    "sensors": [
                                      {
                                        "id": "legacy-1",
                                        "provider": "windy",
                                        "label": "Legacy Sensor"
                                      }
                                    ]
                                  }
                                ]
                                """)));

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].location", is("Legacy Spot"))
                .body("[0].sensors.size()", is(1))
                .body("[0].sensors[0].id", is("legacy-1"))
                .body("[0].sensors[0].label", is("Legacy Sensor"));

        verifyMockServerRequest("GET", "/api/spots", 1);
    }

    // ========================================================================
    // Provider request-header plumbing (shared by every provider)
    // ========================================================================

    @Test
    void requestHeadersAreSentUpstream(RequestSpecification spec) {
        // The probe provider declares a header no real provider uses, and MockServer only answers when
        // it actually arrives: if ProxyService stopped applying provider.headers this call never matches.
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/probe/1").withHeader("X-Test-Header", "sent"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("{}")));

        spec.contentType(ContentType.JSON)
                .body("""
                        {
                          "sensor": {
                            "id": "1",
                            "provider": "probe"
                          }
                        }
                        """)
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windMax", is(7.5f))
                .body("[0].timestamp", is(1700000000));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/api/probe/1").withHeader("X-Test-Header", "sent"),
                VerificationTimes.exactly(1));
    }

    @Test
    void providersWithoutExtraHeadersSendOnlyAccept(RequestSpecification spec) {
        String sensorId = "accept-only";

        mockServerClient
                .when(request()
                        .withMethod("GET")
                        .withPath("/api/windy/" + sensorId)
                        .withHeader("Accept", "application/json"))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(json("""
                                {
                                  "status": "success",
                                  "response": {
                                    "info": { "id": "accept-only", "name": "Accept Only Station" },
                                    "data": [
                                      {
                                        "wind_max": 9.0,
                                        "wind_avg": 6.5,
                                        "wind_min": 4.0,
                                        "wind_direction": 270.0,
                                        "timestamp": 1700000000
                                      }
                                    ]
                                  },
                                  "time": 1700000000
                                }
                                """)));

        spec.contentType(ContentType.JSON)
                .body("""
                        {
                          "sensor": {
                            "id": "%s",
                            "provider": "windy"
                          }
                        }
                        """.formatted(sensorId))
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].timestamp", is(1700000000));

        // Windy leaves requestHeaders at its default, so nothing beyond Accept may reach upstream.
        mockServerClient.verify(
                request().withMethod("GET").withPath("/api/windy/" + sensorId).withHeader("X-Test-Header", "sent"),
                VerificationTimes.exactly(0));
    }

    // ========================================================================
    // Windguru provider (station_data columnar payload)
    // ========================================================================

    /** Mirrors the planned "Dahab – Assalah" spots entry: provider windguru, id 15693. */
    private static final String WINDGURU_STATION_ID = "15693";

    /** Trimmed live capture of station 15693; index 3 is published with a null wind_avg (a gap). */
    private static final String WINDGURU_PAYLOAD = """
                    {
                      "unixtime": [1793785260, 1793785500, 1793785980, 1793786220, 1793786700, 1793787000, 1793787180, 1793787360],
                      "wind_avg": [23.1, 23.3, 24.6, null, 24.3, 24.8, 24.3, 24.3],
                      "wind_max": [26, 27.6, 28, 27.4, 28.3, 28.3, 28.7, 28.7],
                      "wind_min": [20.2, 19.4, 19.4, 21.1, 20, 21.5, 21, 20.8],
                      "wind_direction": [10, 42, 21, 67, 6, 22, 16, 37],
                      "temperature": [25.5, 25.4, 25.3, 25.4, 25.5, 25.4, 25.6, 25.6],
                      "rh": [null, null, null, null, null, null, null, null],
                      "mslp": [760.9, 760.2, 760.2, 760.2, 760.2, 760.2, 760.2, 760.2],
                      "gustiness": [13, 17, 17, 13, 17, 14, 16, 16],
                      "sunrise": "05:58",
                      "sunset": "16:52",
                      "startstamp": 1793782026,
                      "endstamp": 1793787426,
                      "tzoffset": 7200
                    }
                    """;

    private void stubWindguruResponse(String body) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/windguru/" + WINDGURU_STATION_ID))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(new StringBody(body)));
    }

    @Test
    void windguruProviderReturnsNormalizedReadings(RequestSpecification spec) {
        stubWindguruResponse(WINDGURU_PAYLOAD);

        spec.contentType(ContentType.JSON)
                .body("""
                        {
                          "readingWindow": 86400,
                          "numberOfReadings": 100,
                          "sensor": {
                            "id": "%s",
                            "provider": "windguru"
                          }
                        }
                        """.formatted(WINDGURU_STATION_ID))
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(7))
                // Published knots and epoch seconds are carried through unchanged, oldest-first.
                .body("[0].windMin", is(20.2f))
                .body("[0].windAvg", is(23.1f))
                .body("[0].windMax", is(26.0f))
                .body("[0].windDirection", is(10.0f))
                .body("[0].timestamp", is(1793785260))
                .body("[1].timestamp", is(1793785500))
                .body("[2].timestamp", is(1793785980))
                .body("[3].timestamp", is(1793786700))
                .body("[4].timestamp", is(1793787000))
                .body("[5].timestamp", is(1793787180))
                .body("[6].timestamp", is(1793787360));

        verifyMockServerRequest("GET", "/api/windguru/" + WINDGURU_STATION_ID, 1);
    }

    @Test
    void windguruCallCarriesRefererHeader(RequestSpecification spec) {
        // Windguru answers 401 for iapi.php without a windguru.cz referer, so the expectation demands it:
        // drop the shared header plumbing or the provider override and nothing matches upstream.
        mockServerClient
                .when(request()
                        .withMethod("GET")
                        .withPath("/api/windguru/" + WINDGURU_STATION_ID)
                        .withHeader("Referer", "https://www.windguru.cz/station/" + WINDGURU_STATION_ID))
                .respond(response()
                        .withStatusCode(200)
                        .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                        .withBody(new StringBody(WINDGURU_PAYLOAD)));

        spec.contentType(ContentType.JSON)
                .body("""
                        {
                          "sensor": {
                            "id": "%s",
                            "provider": "windguru"
                          }
                        }
                        """.formatted(WINDGURU_STATION_ID))
                .when()
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(5));

        mockServerClient.verify(
                request()
                        .withMethod("GET")
                        .withPath("/api/windguru/" + WINDGURU_STATION_ID)
                        .withHeader("Referer", "https://www.windguru.cz/station/" + WINDGURU_STATION_ID),
                VerificationTimes.exactly(1));
    }

    @Test
    void windguruGapsAndNoDataResponsesAreHandled(RequestSpecification spec) {
        // Case A: published nulls are gaps, never zero-speed readings.
        stubWindguruResponse(WINDGURU_PAYLOAD);
        postWindguruRequest(spec, 86400, 100)
                .then()
                .statusCode(200)
                .body("size()", is(7))
                .body("timestamp", not(hasItem(1793786220)));

        // Case B: a valid range with no readings answers as a bare [] — one empty reading, no exception.
        mockServerClient.reset();
        stubWindguruResponse("[]");
        postWindguruRequest(spec, 3600, 5)
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windMax", is(0.0f))
                .body("[0].windAvg", is(0.0f))
                .body("[0].timestamp", is(0));
    }

    @Test
    void windguruUpstreamFailureIsReported(RequestSpecification spec) {
        // The real-world failure mode: Windguru answers 401 when the Referer is missing or rejected.
        mockServerClient
                .when(request().withMethod("GET").withPath("/api/windguru/" + WINDGURU_STATION_ID))
                .respond(response().withStatusCode(401));

        // Existing behaviour for any provider whose upstream fails: 500 carrying the upstream-cause message.
        postWindguruRequest(spec, 3600, 5)
                .then()
                .statusCode(500)
                .body("message", containsString("Upstream call failed with status 401"));

        verifyMockServerRequest("GET", "/api/windguru/" + WINDGURU_STATION_ID, 1);
    }

    private Response postWindguruRequest(RequestSpecification spec, int window, int count) {
        String body =
                count == 0 ? """
                        {
                          "sensor": {
                            "id": "%s",
                            "provider": "windguru"
                          }
                        }
                        """.formatted(WINDGURU_STATION_ID) : """
                        {
                          "readingWindow": %d,
                          "numberOfReadings": %d,
                          "sensor": {
                            "id": "%s",
                            "provider": "windguru"
                          }
                        }
                        """.formatted(window, count, WINDGURU_STATION_ID);

        return spec.contentType(ContentType.JSON).body(body).when().post("/sensor-data");
    }

    private void verifyMockServerRequest(String method, String path, int times) {
        mockServerClient.verify(request().withMethod(method).withPath(path), VerificationTimes.exactly(times));
    }

    /**
     * Test-only provider that declares an upstream header no real provider uses, proving the shared
     * {@code WindDataProvider#requestHeaders} hook is applied by {@code ProxyService}. It takes its URL
     * from the "probe" provider config exactly like a real provider and answers with a fixed reading, so
     * the test asserts on transport rather than parsing. Active only in this spec ({@code spec.name}).
     */
    @Singleton
    @Requires(property = "spec.name", value = "WindSensorControllerMockServerTest")
    static class HeaderProbeProvider extends BaseWindyDataProvider<WindyMeasurement> {

        static final String NAME = "probe";

        HeaderProbeProvider(@Named(NAME) WindDataProviderConfig config, ObjectMapper objectMapper) {
            super(config, objectMapper);
        }

        @Override
        public String getCallUrl(String sensorId) {
            return getUrl().formatted(sensorId);
        }

        @Override
        public Map<String, String> requestHeaders(String sensorId) {
            return Map.of("X-Test-Header", "sent");
        }

        @Override
        public Try<List<SensorDataDTO>> extractTimedReadings(
                String sensorId, String response, int readingWindowSeconds, int numberOfReadings) {
            return Try.success(List.of(new SensorDataDTO(7.5f, 6.5f, 4.0f, 270.0f, 1700000000L)));
        }

        @Override
        public SensorDataDTO mapToDTO(WindyMeasurement measurement) {
            throw new UnsupportedOperationException("the probe provider never deserializes a payload");
        }

        @Override
        public SensorDataDTO getLastReading(List<WindyMeasurement> data) {
            throw new UnsupportedOperationException("the probe provider never deserializes a payload");
        }
    }
}
