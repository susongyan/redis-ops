package io.github.susongyan.redisops.worker.runtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PendingTargetBatchTest {
    @Test
    void pendingCannotBeReadAsSuccessAndContainsNoCommandPayload() {
        var previous = new TargetCheckpoint("epoch", 1, "repl", 10, 0, Instant.now());
        var next = new TargetCheckpoint("epoch", 1, "repl", 20, 0, Instant.now());
        byte[] pending = PendingTargetBatch.encode(previous, next);
        assertTrue(PendingTargetBatch.isPending(pending));
        var error = assertThrows(SyncBlockedException.class, () -> TargetCheckpoint.decode(pending));
        assertEquals("BLOCKED_TARGET_BATCH_UNCONFIRMED", error.reason());
        assertFalse(new String(pending, StandardCharsets.US_ASCII).contains("\t"));
        assertEquals(next.appliedOffset(), TargetCheckpoint.decode(next.encode()).appliedOffset());
        assertFalse(java.util.Arrays.equals(pending, PendingTargetBatch.encode(previous, next)));
    }
}
