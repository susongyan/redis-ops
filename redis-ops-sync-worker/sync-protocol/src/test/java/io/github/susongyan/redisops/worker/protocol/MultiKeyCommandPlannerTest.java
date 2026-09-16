package io.github.susongyan.redisops.worker.protocol;

import io.github.susongyan.redisops.sync.contract.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiKeyCommandPlannerTest {
    private final SyncCommandPolicy v2 = new SyncCommandPolicy(false, true, Set.of(), "v2");
    private final List<String[]> commands = List.of(
            new String[]{"MSETNX", "{biz}:a", "1", "{biz}:b", "2"},
            new String[]{"RENAME", "{biz}:a", "{biz}:b"}, new String[]{"RENAMENX", "{biz}:a", "{biz}:b"},
            new String[]{"SMOVE", "{biz}:a", "{biz}:b", "value"},
            new String[]{"LMOVE", "{biz}:a", "{biz}:b", "RIGHT", "LEFT"},
            new String[]{"RPOPLPUSH", "{biz}:a", "{biz}:b"}, new String[]{"COPY", "{biz}:a", "{biz}:b"},
            new String[]{"BITOP", "AND", "{biz}:out", "{biz}:a", "{biz}:b"},
            new String[]{"SUNIONSTORE", "{biz}:out", "{biz}:a", "{biz}:b"},
            new String[]{"SINTERSTORE", "{biz}:out", "{biz}:a", "{biz}:b"},
            new String[]{"SDIFFSTORE", "{biz}:out", "{biz}:a", "{biz}:b"},
            new String[]{"PFMERGE", "{biz}:out", "{biz}:a", "{biz}:b"},
            new String[]{"ZUNIONSTORE", "{biz}:out", "2", "{biz}:a", "{biz}:b"},
            new String[]{"ZINTERSTORE", "{biz}:out", "2", "{biz}:a", "{biz}:b"},
            new String[]{"ZDIFFSTORE", "{biz}:out", "2", "{biz}:a", "{biz}:b"});

    @Test
    void everyFirstBatchCommandHasExplicitVersionAndTopologyAdmission() {
        for (String[] args : commands) {
            assertEquals(CommandPlan.Disposition.BLOCK,
                    new CommandPlanner(filter(), false).plan(command(args)).disposition());
            for (boolean cluster : new boolean[]{false, true}) {
                var result = planner(cluster).plan(command(args));
                assertEquals(CommandPlan.Disposition.APPLY, result.disposition(), args[0]);
                assertEquals(1, result.commands().size());
                assertEquals(cluster ? RedisSlot.of("{biz}:a") : -1, result.commands().get(0).slot());
                var outside = args.clone();
                for (int i = 1; i < outside.length; i++)
                    outside[i] = outside[i].replace("{biz}:", "{other}:");
                assertEquals(CommandPlan.Disposition.SKIP, planner(cluster).plan(command(outside)).disposition(),
                        args[0]);
            }
        }
    }

    @Test
    void exhaustsKeyScopeCombinationsForEveryFirstBatchCommand() {
        for (String[] args : commands) {
            var original = command(args);
            var description = CommandKeySemantics.describe(args[0], original.arguments()).orElseThrow();
            int count = description.keys().size();
            for (int mask = 0; mask < (1 << count); mask++) {
                String[] candidate = args.clone();
                boolean insideWrite = false, outsideWrite = false, outsideRead = false;
                for (int i = 0; i < count; i++) {
                    var key = description.keys().get(i);
                    boolean inside = (mask & (1 << i)) != 0;
                    if (!inside)
                        candidate[key.index()] = candidate[key.index()].replace("{biz}:", "{other}:");
                    if (key.role() != CommandKeySemantics.Role.READ) {
                        insideWrite |= inside;
                        outsideWrite |= !inside;
                    }
                    if (key.role() != CommandKeySemantics.Role.WRITE)
                        outsideRead |= !inside;
                }
                for (boolean cluster : new boolean[]{false, true}) {
                    var result = planner(cluster).plan(command(candidate));
                    String context = args[0] + " mask=" + mask + " cluster=" + cluster;
                    if (!insideWrite)
                        assertEquals(CommandPlan.Disposition.SKIP, result.disposition(), context);
                    else if (outsideWrite)
                        assertEquals("BLOCKED_CROSS_SCOPE_WRITE", result.reason(), context);
                    else if (outsideRead)
                        assertEquals("BLOCKED_OUTSIDE_INPUT_DEPENDENCY", result.reason(), context);
                    else
                        assertEquals(CommandPlan.Disposition.APPLY, result.disposition(), context);
                }
            }
        }
    }

    @Test
    void distinguishesOutsideInputFromCrossScopeWritesAndCrossSlot() {
        assertEquals("BLOCKED_OUTSIDE_INPUT_DEPENDENCY", planner(false)
                .plan(command("BITOP", "OR", "{biz}:out", "other:input")).reason());
        assertEquals("BLOCKED_CROSS_SCOPE_WRITE", planner(false)
                .plan(command("RENAME", "{biz}:a", "other:out")).reason());
        assertEquals(CommandPlan.Disposition.SKIP, planner(false)
                .plan(command("COPY", "{biz}:a", "other:out")).disposition());
        var all = new CommandPlanner(new KeyFilter(List.of("*"), List.of()), true, null, v2);
        assertEquals("BLOCKED_CROSS_SLOT", all.plan(command("RENAME", "{a}:a", "{b}:b")).reason());
        assertEquals("BLOCKED_RESERVED_NAMESPACE", all
                .plan(command("COPY", "__redis_ops_sync_ckpt__:x", "{a}:dest")).reason());
        var excluded = new CommandPlanner(new KeyFilter(List.of("*"), List.of("*:excluded")), false, null, v2);
        assertEquals("BLOCKED_OUTSIDE_INPUT_DEPENDENCY", excluded
                .plan(command("SUNIONSTORE", "out", "a:excluded")).reason());
    }

    @Test
    void sameDatabaseCopyDropsSourceDbOptionForTargetDatabaseMapping() {
        var planner = new CommandPlanner(filter(), false, null, v2, 5);
        var result = planner.plan(command("COPY", "{biz}:a", "{biz}:b", "DB", "5", "REPLACE"));
        assertEquals(CommandPlan.Disposition.APPLY, result.disposition());
        assertEquals(List.of("COPY", "{biz}:a", "{biz}:b", "REPLACE"), result.commands().get(0).arguments().stream()
                .map(v -> new String(v, StandardCharsets.UTF_8)).toList());
        assertEquals("BLOCKED_CROSS_DATABASE", planner.plan(command("COPY", "{biz}:a", "{biz}:b", "DB", "0")).reason());
        assertEquals("BLOCKED_COMMAND_ARGUMENTS", planner.plan(command("COPY", "{biz}:a", "{biz}:b", "DB")).reason());
    }

    @Test
    void binaryKeysRemainByteExactAndAdditionalBlocksRemainEffective() {
        var bytes = new ArrayList<>(command("RENAME", "a", "b").arguments());
        bytes.set(1, new byte[]{(byte) 0xff, 0, 1});
        bytes.set(2, new byte[]{(byte) 0xfe, 0, 2});
        var all = new CommandPlanner(new KeyFilter(List.of(), List.of()), false, null, v2);
        var plan = all.plan(new ReplicationCommand("RENAME", bytes, 0, 1));
        assertEquals(CommandPlan.Disposition.APPLY, plan.disposition());
        assertArrayEquals(bytes.get(1), plan.commands().get(0).arguments().get(1));
        var restricted = new CommandPlanner(filter(), false, null,
                new SyncCommandPolicy(false, true, Set.of("COPY"), "v2"));
        assertEquals("BLOCKED_COMMAND_POLICY", restricted.plan(command("COPY", "{biz}:a", "{biz}:b")).reason());
        for (String name : List.of("EVAL", "EVALSHA", "MULTI", "EXEC", "CUSTOM", "SORT"))
            assertEquals(CommandPlan.Disposition.BLOCK, all.plan(command(name, "a")).disposition());
    }
    private KeyFilter filter() {
        return new KeyFilter(List.of("{biz}:*"), List.of());
    }
    private CommandPlanner planner(boolean cluster) {
        return new CommandPlanner(filter(), cluster, null, v2);
    }
    private ReplicationCommand command(String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(a -> a.getBytes(StandardCharsets.UTF_8)).toList(), 0, 1);
    }
}
