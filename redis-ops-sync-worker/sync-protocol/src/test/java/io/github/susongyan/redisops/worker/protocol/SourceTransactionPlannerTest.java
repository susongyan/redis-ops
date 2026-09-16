package io.github.susongyan.redisops.worker.protocol;

import io.github.susongyan.redisops.sync.contract.SyncCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransactionPlannerTest {
    private final SyncCommandPolicy policy = new SyncCommandPolicy(false, true, Set.of(), "v2");
    @Test
    void doesNotPartiallyFilterSplittableCommandsOrWholeTransactions() {
        for (boolean cluster : new boolean[]{false, true}) {
            var planner = planner(cluster);
            assertEquals("BLOCKED_TRANSACTION_MIXED_SCOPE", planner.plan(unit(
                    command("MSET", "{biz}:a", "1", "outside", "2")), 0).plan().reason());
            assertEquals("BLOCKED_TRANSACTION_MIXED_SCOPE", planner.plan(unit(
                    command("INCR", "{biz}:a"), command("INCR", "outside")), 0).plan().reason());
            assertEquals("BLOCKED_TRANSACTION_MIXED_SCOPE", planner.plan(unit(
                    command("BITOP", "OR", "{biz}:a", "outside")), 0).plan().reason());
            assertEquals(CommandPlan.Disposition.SKIP, planner.plan(unit(
                    command("COPY", "{biz}:a", "outside")), 0).plan().disposition());
            var all = planner.plan(unit(command("INCR", "{biz}:a"), command("INCR", "{biz}:b")), 0);
            assertEquals(CommandPlan.Disposition.APPLY, all.plan().disposition());
            assertEquals(2, all.plan().commands().size());
        }
    }
    @Test
    void requiresWholeTransactionSameSlotNotMerelyEachCommand() {
        var planner = new SourceTransactionPlanner(new KeyFilter(List.of(), List.of()), true, policy, 0);
        assertEquals("BLOCKED_TRANSACTION_CROSS_SLOT", planner.plan(unit(
                command("INCR", "{a}:x"), command("INCR", "{b}:x")), 0).plan().reason());
    }
    @Test
    void tracksSelectedDatabaseButNeverPartiallyExecutesCrossDbTransaction() {
        var planner = planner(false);
        assertEquals("BLOCKED_TRANSACTION_MIXED_SCOPE", planner.plan(unit(command("INCR", "{biz}:a"),
                command("SELECT", "1"), command("INCR", "{biz}:a")), 0).plan().reason());
        var outside = planner.plan(unit(command("SELECT", "1"), command("INCR", "{biz}:a")), 0);
        assertEquals(CommandPlan.Disposition.SKIP, outside.plan().disposition());
        assertEquals(1, outside.sourceDatabase());
        var selectAtEnd = planner.plan(unit(command("INCR", "{biz}:a"), command("SELECT", "1")), 0);
        assertEquals(CommandPlan.Disposition.APPLY, selectAtEnd.plan().disposition());
        assertEquals(1, selectAtEnd.sourceDatabase());
        assertEquals(1, selectAtEnd.plan().commands().size());
    }
    @Test
    void rejectsUnknownCommandsInternalKeysAndInvalidSelectEvenOutsideScope() {
        var planner = planner(false);
        for (var command : List.of(command("EVAL", "private-script"), command("FLUSHDB"),
                command("INCR", "__redis_ops_sync_ckpt__:x"), command("SELECT", "-1")))
            assertEquals(CommandPlan.Disposition.BLOCK, planner.plan(unit(command), 1).plan().disposition());
    }
    private SourceTransactionPlanner planner(boolean cluster) {
        return new SourceTransactionPlanner(new KeyFilter(List.of("{biz}:*"), List.of()), cluster, policy, 0);
    }
    private SourceTransactionAssembler.Unit unit(ReplicationCommand... commands) {
        return new SourceTransactionAssembler.Unit(true, List.of(commands), 0, 100);
    }
    private ReplicationCommand command(String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(a -> a.getBytes(StandardCharsets.UTF_8)).toList(), 1, 2);
    }
}
