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

@MicronautTest(environments = "test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class WindSensorControllerIntegrationTest implements TestPropertyProvider {

    @Container
    static MockServerContainer mockServerContainer = new MockServerContainer(
            DockerImageName.parse("mockserver/mockserver:5.15.0")
    );

    static MockServerClient mockServerClient;

    @Override
    public @NonNull Map<String, String> getProperties() {
        mockServerContainer.start();
        mockServerClient = new MockServerClient(
                mockServerContainer.getHost(),
                mockServerContainer.getServerPort()
        );
        String mockServerUrl = mockServerContainer.getEndpoint();
        return Map.of(
                "wind-sensor.wind-providers.neduet.url", mockServerUrl,
                "wind-sensor.wind-providers.windy.url", mockServerUrl,
                "wind-sensor.spots-data-url", mockServerUrl + "/spots",
                "wind-sensor.spots-test-data-url", mockServerUrl + "/spots-test",
                "wind-sensor.spots-dahab-url", mockServerUrl + "/spots-dahab",
                "wind-sensor.spots-data-media-type", "application/json"
        );
    }

    @BeforeEach
    void setUp() {
        mockServerClient.reset();
    }

    @Test
    void shouldReturnSensorDataForNeduetProvider(RequestSpecification spec) {
        // Mock Neduet API response
        org.mockserver.model.JsonBody neduetResponse = json("""
                [
                  {
                    "name": "Test Station",
                    "id": "sensor-123",
                    "data": [
                      {
                        "avr": 5.2,
                        "min": 3.0,
                        "max": 8.5,
                        "dir": 180.0,
                        "timestamp": 1700000000
                      },
                      {
                        "avr": 6.0,
                        "min": 4.0,
                        "max": 9.0,
                        "dir": 190.0,
                        "timestamp": 1700000060
                      }
                    ]
                  }
                ]
                """);

        mockServerClient
                .when(request().withMethod("GET").withPath("/"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(neduetResponse)
                );

        String requestJson = """
                {
                  "readingWindow": 3600,
                  "numberOfReadings": 5,
                  "sensor": {
                    "id": "sensor-123",
                    "provider": "neduet",
                    "label": "Test Sensor"
                  }
                }
                """;

        spec.contentType(ContentType.JSON)
                .when()
                .body(requestJson)
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].windAvg", is(5.2f))
                .body("[0].windMax", is(8.5f))
                .body("[0].windMin", is(3.0f))
                .body("[0].windDirection", is(180.0f))
                .body("[0].timestamp", is(1700000000))
                .body("[1].windAvg", is(6.0f));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/"),
                VerificationTimes.exactly(1)
        );
    }

    @Test
    void shouldReturnEmptySensorDataForEmptyNeduetResponse(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(json("[]"))
                );

        String requestJson = """
                {
                  "sensor": {
                    "id": "sensor-123",
                    "provider": "neduet",
                    "label": "Test Sensor"
                  }
                }
                """;

        spec.contentType(ContentType.JSON)
                .when()
                .body(requestJson)
                .post("/sensor-data")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].windAvg", is(0.0f))
                .body("[0].windMax", is(0.0f));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/"),
                VerificationTimes.exactly(1)
        );
    }

    @Test
    void shouldReturnSpotsData(RequestSpecification spec) {
        org.mockserver.model.JsonBody spotsResponse = json("""
                [
                  {
                    "location": "Dahab",
                    "readingWindow": 3600,
                    "numberOfReadings": 5,
                    "sensors": [
                      {
                        "id": "sensor-1",
                        "provider": "neduet",
                        "label": "Dahab Sensor 1"
                      },
                      {
                        "id": "sensor-2",
                        "provider": "windy",
                        "label": "Dahab Sensor 2"
                      }
                    ]
                  },
                  {
                    "location": "Sharm",
                    "readingWindow": 1800,
                    "numberOfReadings": 3,
                    "sensors": [
                      {
                        "id": "sensor-3",
                        "provider": "neduet",
                        "label": "Sharm Sensor 1"
                      }
                    ]
                  }
                ]
                """);

        mockServerClient
                .when(request().withMethod("GET").withPath("/spots"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(spotsResponse)
                );

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].location", is("Dahab"))
                .body("[0].sensors.size()", is(2))
                .body("[1].location", is("Sharm"))
                .body("[1].sensors.size()", is(1));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/spots"),
                VerificationTimes.exactly(1)
        );
    }

    @Test
    void shouldReturnSpotsDataWithDebugFlag(RequestSpecification spec) {
        org.mockserver.model.JsonBody spotsResponse = json("""
                [
                  {
                    "location": "TestSpot",
                    "readingWindow": 600,
                    "numberOfReadings": 2,
                    "sensors": [
                      {
                        "id": "test-sensor",
                        "provider": "neduet",
                        "label": "Test Spot Sensor"
                      }
                    ]
                  }
                ]
                """);

        mockServerClient
                .when(request().withMethod("GET").withPath("/spots-test"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(spotsResponse)
                );

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data?isDebug=true")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].location", is("TestSpot"))
                .body("[0].sensors.size()", is(1));

        // Verify the test/debug endpoint was called, not the production one
        mockServerClient.verify(
                request().withMethod("GET").withPath("/spots-test"),
                VerificationTimes.exactly(1)
        );
    }

    @Test
    void shouldReturnSpotsDataDahab(RequestSpecification spec) {
        org.mockserver.model.JsonBody spotsResponse = json("""
                [
                  {
                    "location": "Dahab Center",
                    "readingWindow": 7200,
                    "numberOfReadings": 10,
                    "sensors": [
                      {
                        "id": "dahab-1",
                        "provider": "neduet",
                        "label": "Dahab Center Sensor"
                      }
                    ]
                  }
                ]
                """);

        mockServerClient
                .when(request().withMethod("GET").withPath("/spots-dahab"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(spotsResponse)
                );

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data-dahab")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].location", is("Dahab Center"))
                .body("[0].readingWindow", is(7200))
                .body("[0].numberOfReadings", is(10))
                .body("[0].sensors.size()", is(1))
                .body("[0].sensors[0].id", is("dahab-1"));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/spots-dahab"),
                VerificationTimes.exactly(1)
        );
    }

    @Test
    void shouldReturnEmptySpotsDataForEmptyResponse(RequestSpecification spec) {
        mockServerClient
                .when(request().withMethod("GET").withPath("/spots"))
                .respond(
                        response()
                                .withStatusCode(200)
                                .withHeaders(new Header("Content-Type", "application/json; charset=utf-8"))
                                .withBody(json("[]"))
                );

        spec.contentType(ContentType.JSON)
                .when()
                .get("/spots-data")
                .then()
                .statusCode(200)
                .body("size()", is(0));

        mockServerClient.verify(
                request().withMethod("GET").withPath("/spots"),
                VerificationTimes.exactly(1)
        );
    }
}