package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceExecutionBatcherTest {
    @Test
    void separatesOrdinaryCommandsAndNeverAdvancesToOpenTransaction() {
        var batcher = new SourceExecutionBatcher();
        var first = batcher.accept(List.of(command(1, "INCR", "a"), command(2, "MULTI"), command(3, "INCR", "b")), 0);
        assertEquals(1, first.size());
        assertFalse(first.get(0).transaction());
        assertEquals(1, first.get(0).endOffset());
        assertTrue(batcher.hasOpenTransaction());
        // Spool replay overlaps the live queue: do not assemble or apply the same prefix twice.
        var next = batcher.accept(List.of(command(2, "MULTI"), command(3, "INCR", "b"), command(4, "INCR", "c"),
                command(5, "EXEC"), command(6, "INCR", "d")), 1);
        assertEquals(2, next.size());
        assertTrue(next.get(0).transaction());
        assertEquals(2, next.get(0).commands().size());
        assertEquals(5, next.get(0).endOffset());
        assertFalse(next.get(1).transaction());
        assertEquals(6, next.get(1).endOffset());
    }
    @Test
    void restartReplaysOnlyFromCommittedBoundary() {
        var restarted = new SourceExecutionBatcher();
        var units = restarted.accept(List.of(command(1, "INCR", "a"), command(2, "MULTI"),
                command(3, "INCR", "b"), command(4, "EXEC")), 1);
        assertEquals(1, units.size());
        assertTrue(units.get(0).transaction());
        assertEquals(4, units.get(0).endOffset());
        assertDoesNotThrow(restarted::endOfInput);
    }
    private ReplicationCommand command(long offset, String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(a -> a.getBytes(StandardCharsets.US_ASCII)).toList(), offset, offset);
    }
}
