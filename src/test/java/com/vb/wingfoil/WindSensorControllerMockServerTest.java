package com.vb.wingfoil;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
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

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

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
                "wind-sensor.wind-providers.windy.url", mockServerContainer.getEndpoint() + "/api/windy/%s",
                "wind-sensor.wind-providers.neduet.url", mockServerContainer.getEndpoint() + "/api/neduet",
                "wind-sensor.spots-data-url", mockServerContainer.getEndpoint() + "/api/spots",
                "wind-sensor.spots-test-data-url", mockServerContainer.getEndpoint() + "/api/spots-test",
                "wind-sensor.spots-dahab-url", mockServerContainer.getEndpoint() + "/api/spots-dahab",
                "wind-sensor.spots-data-media-type", "application/json");
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
                                        "label": "Anapa Beach"
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
                                        "label": "Sochi Port"
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
                .body("[1].location", is("Sochi"))
                .body("[1].readingWindow", is(7200));

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
                                        "label": "Test Sensor"
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
                .body("[0].sensors[0].id", is("test-1"));

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
                                        "label": "Dahab Blue Hole"
                                      },
                                      {
                                        "id": "dahab-2",
                                        "provider": "windy",
                                        "label": "Dahab Lagoon"
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
                .body("[0].sensors[1].id", is("dahab-2"));

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

    private void verifyMockServerRequest(String method, String path, int times) {
        mockServerClient.verify(request().withMethod(method).withPath(path), VerificationTimes.exactly(times));
    }
}
