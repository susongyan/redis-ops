package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Pins the copy discipline of {@link TargetCommandSession.RestoreRequest}: exactly one defensive
 * copy at construction (the request queues its own payload) and zero copies per access (large keys
 * flow through the restore queue without heap multiplication).
 */
class RestoreRequestTest {

    @Test
    void constructorTakesExactlyOneDefensiveCopy() {
        byte[] key = "key".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[]{1, 2, 3};
        TargetCommandSession.RestoreRequest request = new TargetCommandSession.RestoreRequest(key, -1, payload);

        assertNotSame(key, request.key());
        assertNotSame(payload, request.payload());

        // Mutating the caller's arrays after construction must not leak into the request.
        key[0] = 'X';
        payload[0] = 9;
        assertEquals('k', request.key()[0]);
        assertEquals(1, request.payload()[0]);
    }

    @Test
    void accessorsReturnTheStoredArrayWithoutCopying() {
        TargetCommandSession.RestoreRequest request = new TargetCommandSession.RestoreRequest(new byte[]{'k'}, -1,
                new byte[]{1});

        assertSame(request.key(), request.key());
        assertSame(request.payload(), request.payload());
    }

    @Test
    void estimatedBytesCountsKeyAndPayloadPlusWireOverhead() {
        TargetCommandSession.RestoreRequest request = new TargetCommandSession.RestoreRequest(new byte[10], -1,
                new byte[100]);

        assertEquals(10 + 100 + 128L, request.estimatedBytes());
    }
}
