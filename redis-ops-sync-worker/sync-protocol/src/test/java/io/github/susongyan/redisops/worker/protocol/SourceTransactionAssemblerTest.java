package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransactionAssemblerTest {
    @Test
    void releasesCompletedTransactionsDuringMillionCommandStream() {
        var assembler = new SourceTransactionAssembler();
        long offset = 0;
        for (int transaction = 0; transaction < 10_000; transaction++) {
            assembler.accept(command(++offset, "MULTI"));
            for (int item = 0; item < 100; item++)
                assembler.accept(command(++offset, "SET", "business:" + offset, "value"));
            assertEquals(100, assembler.accept(command(++offset, "EXEC")).orElseThrow().commands().size());
            assertEquals(0, assembler.bufferedCommands());
            assertEquals(0, assembler.bufferedBytes());
        }
        assembler.endOfInput();
    }
    @Test
    void emitsOnlyAtExecAcrossArbitraryReadBatches() {
        var assembler = new SourceTransactionAssembler();
        assertTrue(assembler.accept(command(1, "MULTI")).isEmpty());
        for (int i = 2; i < 302; i++)
            assertTrue(assembler.accept(command(i, "INCR", "business")).isEmpty());
        assertEquals(300, assembler.bufferedCommands());
        var unit = assembler.accept(command(302, "EXEC")).orElseThrow();
        assertTrue(unit.transaction());
        assertEquals(300, unit.commands().size());
        assertEquals(1, unit.startOffset());
        assertEquals(302, unit.endOffset());
        assertFalse(assembler.hasOpenTransaction());
        assertEquals(0, assembler.bufferedBytes());
        assertFalse(assembler.accept(command(303, "SET", "a", "b")).orElseThrow().transaction());
    }
    @Test
    void limitsCommandCountAndBytesBeforeRetainingNextCommand() {
        var count = new SourceTransactionAssembler(1, 1000);
        count.accept(command(1, "MULTI"));
        count.accept(command(2, "INCR", "a"));
        assertEquals("BLOCKED_TRANSACTION_LIMIT", assertThrows(IllegalStateException.class,
                () -> count.accept(command(3, "INCR", "secret-key"))).getMessage());
        assertEquals(0, count.bufferedCommands());
        assertThrows(IllegalStateException.class, () -> count.accept(command(4, "EXEC")));
        var bytes = new SourceTransactionAssembler(10, 30);
        bytes.accept(command(1, "MULTI"));
        assertThrows(IllegalStateException.class, () -> bytes.accept(command(2, "SET", "a", "private-value")));
        assertEquals(0, bytes.bufferedBytes());
    }
    @Test
    void rejectsNestedOrOrphanMarkersAndIncompleteTerminalInput() {
        for (String name : new String[]{"EXEC", "DISCARD", "WATCH", "UNWATCH"}) {
            var assembler = new SourceTransactionAssembler();
            assertThrows(IllegalStateException.class, () -> assembler.accept(command(1, name)));
        }
        var nested = new SourceTransactionAssembler();
        nested.accept(command(1, "MULTI"));
        assertThrows(IllegalStateException.class, () -> nested.accept(command(2, "MULTI")));
        var partial = new SourceTransactionAssembler();
        partial.accept(command(1, "MULTI"));
        partial.accept(command(2, "INCR", "a"));
        assertEquals("BLOCKED_TRANSACTION_INCOMPLETE",
                assertThrows(IllegalStateException.class, partial::endOfInput).getMessage());
        assertEquals(0, partial.bufferedCommands());
    }
    @Test
    void rejectsNonMonotonicOffsetsAndCountsRespWithoutCopyingValues() {
        var assembler = new SourceTransactionAssembler();
        assembler.accept(command(2, "MULTI"));
        assertThrows(IllegalStateException.class, () -> assembler.accept(command(2, "EXEC")));
        assertEquals(15, command(1, "MULTI").encodedBytes());
        assertEquals(27, command(1, "SET", "a", "b").encodedBytes());
    }
    private ReplicationCommand command(long offset, String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(a -> a.getBytes(StandardCharsets.UTF_8)).toList(),
                offset, offset);
    }
}
