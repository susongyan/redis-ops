package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncCommandObservationsTest {
    @Test
    void countsOnlyConfirmedBatchesAndSeparatesSourcesFromTargetOperations() {
        var before = SyncCommandObservations.snapshot();
        var batch = new SyncCommandObservations.Batch();
        var command = new ReplicationCommand("DEL", List.of("DEL".getBytes(StandardCharsets.US_ASCII)), 1, 2);
        var operation = new CommandPlan.PlannedCommand(-1, command.arguments());
        batch.ordinary(command, new CommandPlan(CommandPlan.Disposition.APPLY, List.of(operation, operation), null));
        assertEquals(before, SyncCommandObservations.snapshot());
        batch.confirmed();
        var after = SyncCommandObservations.snapshot();
        assertEquals(before.get("appliedSourceCommands") + 1, after.get("appliedSourceCommands"));
        assertEquals(before.get("targetOperations") + 2, after.get("targetOperations"));
        assertEquals(before.get("expandedOrdinarySourceCommands") + 1, after.get("expandedOrdinarySourceCommands"));
        assertEquals(7, after.size());
    }
}
