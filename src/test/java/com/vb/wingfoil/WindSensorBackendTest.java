package com.vb.wingfoil;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class WindSensorBackendTest {

    @Inject
    ProxyService proxyService;

    @Test
    void testSpotDataFormat() {
        var spots = proxyService.requestSpotsData(false).get();
        assertEquals(2, spots.size());
        assertTrue(spots.get(0).sensors().stream().anyMatch(sensorDTO ->
                sensorDTO.id().equals("1chipru_a4e57cbb42cc"))
        );
    }

}
