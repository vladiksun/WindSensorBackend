package com.vb.wingfoil.tiles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

class CachedTileSerializationTest {

    @Test
    void roundTripsThroughJavaSerialization() throws Exception {
        var original = new CachedTile(new byte[] {1, 2, 3, 4}, 1_700_000_000_000L, 1_700_604_800_000L, "\"abc123\"");

        var restored = serializeAndRestore(original);

        assertArrayEquals(original.png(), restored.png());
        assertEquals(original.fetchedAtEpochMillis(), restored.fetchedAtEpochMillis());
        assertEquals(original.expiresAtEpochMillis(), restored.expiresAtEpochMillis());
        assertEquals(original.etag(), restored.etag());
    }

    @Test
    void supportsNullEtag() throws Exception {
        var original = new CachedTile(new byte[] {9}, 1L, 2L, null);

        var restored = serializeAndRestore(original);

        assertArrayEquals(original.png(), restored.png());
        assertNull(restored.etag());
    }

    private static CachedTile serializeAndRestore(CachedTile tile) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(out)) {
            oos.writeObject(tile);
        }
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            return (CachedTile) ois.readObject();
        }
    }
}
