package io.github.redisops.sync.protocol;

import io.github.redisops.domain.sync.SyncCommandCapabilities;
import io.github.redisops.domain.sync.SyncCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CommandPlanner {
    private static final byte[] INTERNAL_NAMESPACE = "__redis_ops_sync_".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INTERNAL_HEARTBEAT = "__redis_ops_sync_hb__:".getBytes(StandardCharsets.US_ASCII);
    private final KeyFilter filter;
    private final boolean clusterTarget;
    private final byte[] allowedHeartbeat;
    private final SyncCommandPolicy policy;

    public CommandPlanner(KeyFilter filter, boolean clusterTarget) {
        this(filter, clusterTarget, null, SyncCommandPolicy.strict());
    }
    public CommandPlanner(KeyFilter filter, boolean clusterTarget, byte[] allowedHeartbeat) {
        this(filter, clusterTarget, allowedHeartbeat, SyncCommandPolicy.strict());
    }
    public CommandPlanner(
            KeyFilter filter, boolean clusterTarget, byte[] allowedHeartbeat, SyncCommandPolicy policy) {
        this.filter = filter;
        this.clusterTarget = clusterTarget;
        this.allowedHeartbeat = allowedHeartbeat == null ? null : allowedHeartbeat.clone();
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public CommandPlan plan(ReplicationCommand command) {
        List<byte[]> args = command.arguments();
        if (args.isEmpty())
            return CommandPlan.block("empty replication command");
        String name = command.name();
        if (SyncCommandCapabilities.skipped(name))
            return CommandPlan.skip();
        if (SyncCommandCapabilities.hardBlocked(name))
            return CommandPlan.block("command cannot be safely transformed: " + name);
        if (SyncCommandCapabilities.destructive(name) && clusterTarget)
            return CommandPlan.block(name + " requires an explicit all-master target operation");
        if (policy.additionallyBlocks(name))
            return CommandPlan.block("command blocked by task policy: " + name);
        if (SyncCommandCapabilities.destructive(name) && !policy.allowDestructiveCommands())
            return CommandPlan.block("destructive command is disabled by task policy: " + name);
        if (name.equals("FLUSHDB"))
            return new CommandPlan(CommandPlan.Disposition.APPLY, List.of(new CommandPlan.PlannedCommand(-1, args)),
                    null);
        if (name.equals("FLUSHALL")) {
            if (clusterTarget)
                return CommandPlan.block("FLUSHALL requires an explicit all-master target operation");
            return new CommandPlan(CommandPlan.Disposition.APPLY,
                    List.of(new CommandPlan.PlannedCommand(-1,
                            List.of("FLUSHDB".getBytes(StandardCharsets.US_ASCII)))),
                    null);
        }
        if (name.equals("MSET")) {
            if (!policy.allowSafeSplit())
                return CommandPlan.block("safe command splitting is disabled by task policy: MSET");
            return splitMset(args);
        }
        if ((name.equals("DEL") || name.equals("UNLINK")) && args.size() > 2 && !policy.allowSafeSplit())
            return CommandPlan.block("safe command splitting is disabled by task policy: " + name);
        if (name.equals("DEL") || name.equals("UNLINK"))
            return splitKeys(args);
        if (SyncCommandCapabilities.singleKey(name))
            return single(args);
        return CommandPlan.block("unsupported replication command: " + name);
    }

    private CommandPlan single(List<byte[]> args) {
        if (args.size() < 2)
            return CommandPlan.block("command has no key");
        byte[] key = args.get(1);
        if (internalKey(key) && (!internalHeartbeat(key) || !java.util.Arrays.equals(key, allowedHeartbeat)))
            return CommandPlan.skip();
        if (!internalHeartbeat(key) && !filter.accepts(key))
            return CommandPlan.skip();
        return apply(args, RedisSlot.of(key));
    }
    private CommandPlan splitKeys(List<byte[]> args) {
        if (args.size() < 2)
            return CommandPlan.skip();
        List<CommandPlan.PlannedCommand> result = new ArrayList<>();
        for (int i = 1; i < args.size(); i++)
            if (filter.accepts(args.get(i)) && !internalKey(args.get(i))) {
                List<byte[]> one = List.of(args.get(0), args.get(i));
                result.add(new CommandPlan.PlannedCommand(clusterTarget ? RedisSlot.of(args.get(i)) : -1, one));
            }
        return result.isEmpty() ? CommandPlan.skip() : new CommandPlan(CommandPlan.Disposition.APPLY, result, null);
    }
    private CommandPlan splitMset(List<byte[]> args) {
        if (args.size() < 3 || args.size() % 2 == 0)
            return CommandPlan.block("invalid MSET argument count");
        Map<Integer, List<byte[]>> groups = new LinkedHashMap<>();
        for (int i = 1; i < args.size(); i += 2) {
            byte[] key = args.get(i);
            if (!filter.accepts(key) || internalKey(key))
                continue;
            int slot = clusterTarget ? RedisSlot.of(key) : -1;
            List<byte[]> group = groups.computeIfAbsent(slot, x -> {
                var list = new ArrayList<byte[]>();
                list.add(args.get(0));
                return list;
            });
            group.add(key);
            group.add(args.get(i + 1));
        }
        if (groups.isEmpty())
            return CommandPlan.skip();
        return new CommandPlan(CommandPlan.Disposition.APPLY,
                groups.entrySet().stream().map(x -> new CommandPlan.PlannedCommand(x.getKey(), x.getValue())).toList(),
                null);
    }
    private CommandPlan apply(List<byte[]> args, int slot) {
        return new CommandPlan(CommandPlan.Disposition.APPLY,
                List.of(new CommandPlan.PlannedCommand(clusterTarget ? slot : -1, args)), null);
    }
    private static boolean internalKey(byte[] key) {
        return startsWith(key, INTERNAL_NAMESPACE);
    }
    private static boolean internalHeartbeat(byte[] key) {
        return startsWith(key, INTERNAL_HEARTBEAT);
    }
    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++)
            if (key[i] != prefix[i])
                return false;
        return true;
    }
}
