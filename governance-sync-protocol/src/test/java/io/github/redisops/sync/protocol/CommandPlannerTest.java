package io.github.redisops.sync.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandPlannerTest {
    @Test
    void excludesEveryRedisOpsInternalNamespaceKey() {
        CommandPlanner planner = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), false);

        for (String key : List.of("__redis_ops_sync_ckpt__:1", "__redis_ops_sync_fence__:1",
                "__redis_ops_sync_full_progress__:1:0", "__redis_ops_sync_hb__:another-task")) {
            CommandPlan plan = planner.plan(command("SET", key, "internal"));
            assertEquals(CommandPlan.Disposition.SKIP, plan.disposition(), key);
        }
    }

    @Test
    void mapsSourceFlushAllToSelectedStandaloneTargetDatabaseOnly() {
        CommandPlanner planner = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), false);

        CommandPlan plan = planner.plan(command("FLUSHALL"));

        assertEquals(CommandPlan.Disposition.APPLY, plan.disposition());
        assertEquals("FLUSHDB", new String(plan.commands().get(0).arguments().get(0),
                java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void blocksFlushAllForClusterUntilAllMasterOperationIsAvailable() {
        CommandPlanner planner = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), true);

        assertEquals(CommandPlan.Disposition.BLOCK, planner.plan(command("FLUSHALL")).disposition());
    }

    private static byte[] b(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static ReplicationCommand command(String... arguments) {
        List<byte[]> encoded = java.util.Arrays.stream(arguments).map(CommandPlannerTest::b).toList();
        return new ReplicationCommand(arguments[0], encoded, 0, 1);
    }
    @Test
    void splitsMsetByTargetSlotAndFilter() {
        var planner = new CommandPlanner(new KeyFilter(List.of("order:*"), List.of("order:debug:*")), true);
        var plan = planner.plan(new ReplicationCommand("MSET",
                List.of(b("MSET"), b("order:{1}:a"), b("a"), b("order:{2}:b"), b("b"), b("other"), b("c")), 0, 10));
        assertEquals(CommandPlan.Disposition.APPLY, plan.disposition());
        assertEquals(2, plan.commands().size());
    }
    @Test
    void blocksCrossModeSemanticCommand() {
        var planner = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), true);
        assertEquals(CommandPlan.Disposition.BLOCK, planner.plan(new ReplicationCommand("MSETNX",
                List.of(b("MSETNX"), b("a"), b("1"), b("b"), b("2")), 0, 1)).disposition());
    }

    @Test
    void onlyAllowsCurrentTaskHeartbeatThroughReservedNamespace() {
        byte[] taskHeartbeat = b("__redis_ops_sync_hb__:{task-1}:standalone");
        var planner = new CommandPlanner(
                new KeyFilter(List.of("order:*"), List.of()),
                false,
                taskHeartbeat);

        assertEquals(
                CommandPlan.Disposition.APPLY,
                planner.plan(new ReplicationCommand(
                        "SET", List.of(b("SET"), taskHeartbeat, b("1")), 0, 1))
                        .disposition());
        assertEquals(
                CommandPlan.Disposition.SKIP,
                planner.plan(new ReplicationCommand(
                        "SET",
                        List.of(
                                b("SET"),
                                b("__redis_ops_sync_hb__:{task-2}:standalone"),
                                b("1")),
                        1,
                        2))
                        .disposition());
        assertEquals(
                CommandPlan.Disposition.SKIP,
                planner.plan(new ReplicationCommand(
                        "SET",
                        List.of(
                                b("SET"),
                                b("__redis_ops_sync_ckpt__:{task-1}:standalone"),
                                b("1")),
                        2,
                        3))
                        .disposition());
    }
}
