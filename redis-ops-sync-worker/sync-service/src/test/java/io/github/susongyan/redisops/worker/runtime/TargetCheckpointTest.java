package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.RespProtocolException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TargetCheckpointTest {
    @Test
    void roundTripsBinarySafeCheckpointFields() {
        var checkpoint = new TargetCheckpoint("epoch-1", 8, "repl-id", 1234, 2,
                Instant.ofEpochMilli(5678));
        assertEquals(checkpoint, TargetCheckpoint.decode(checkpoint.encode()));
    }

    @Test
    void rejectsForeignReservedKeyContent() {
        assertThrows(RespProtocolException.class, () -> TargetCheckpoint.decode("business".getBytes()));
    }
}
