package io.github.redisops.sync.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CommandPlanner {
    private static final Set<String> SINGLE_KEY = Set.of("SET", "SETEX", "PSETEX", "SETNX", "APPEND", "GETSET",
            "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT", "HSET", "HMSET", "HSETNX", "HDEL", "HINCRBY",
            "HINCRBYFLOAT", "LPUSH", "RPUSH", "LPOP", "RPOP", "LSET", "LTRIM", "LREM", "LINSERT", "SADD", "SREM",
            "ZADD", "ZREM", "ZINCRBY", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZREMRANGEBYLEX", "EXPIRE",
            "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST", "SETBIT", "BITFIELD", "PFADD", "XADD", "XACK", "XDEL",
            "XTRIM", "XSETID", "XGROUP", "RESTORE", "UNLINK");
    private static final Set<String> SPLIT_KEYS = Set.of("DEL", "UNLINK");
    private static final Set<String> UNSUPPORTED_MULTI = Set.of("MSETNX", "RENAME", "RENAMENX", "BITOP",
            "SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE", "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE", "SMOVE",
            "LMOVE", "RPOPLPUSH", "BRPOPLPUSH", "COPY", "SORT", "EVAL", "EVALSHA", "FCALL", "FCALL_RO");
    private static final byte[] INTERNAL_CHECKPOINT = "__redis_ops_sync_ckpt__:".getBytes(StandardCharsets.US_ASCII);
    private final KeyFilter filter;
    private final boolean clusterTarget;

    public CommandPlanner(KeyFilter filter, boolean clusterTarget) {
        this.filter = filter;
        this.clusterTarget = clusterTarget;
    }

    public CommandPlan plan(ReplicationCommand command) {
        List<byte[]> args = command.arguments();
        if (args.isEmpty())
            return CommandPlan.block("empty replication command");
        String name = command.name();
        if (name.equals("SELECT") || name.equals("PING") || name.equals("REPLCONF"))
            return CommandPlan.skip();
        if (name.equals("FLUSHDB") || name.equals("FLUSHALL"))
            return new CommandPlan(CommandPlan.Disposition.APPLY, List.of(new CommandPlan.PlannedCommand(-1, args)),
                    null);
        if (name.equals("MSET"))
            return splitMset(args);
        if (SPLIT_KEYS.contains(name))
            return splitKeys(args);
        if (SINGLE_KEY.contains(name))
            return single(args);
        if (UNSUPPORTED_MULTI.contains(name))
            return CommandPlan.block("command cannot be safely transformed: " + name);
        if (name.equals("MULTI") || name.equals("EXEC") || name.equals("DISCARD"))
            return CommandPlan.block("transaction boundaries must be planned as a transaction");
        return CommandPlan.block("unsupported replication command: " + name);
    }

    private CommandPlan single(List<byte[]> args) {
        if (args.size() < 2)
            return CommandPlan.block("command has no key");
        byte[] key = args.get(1);
        if (internalCheckpoint(key) || !filter.accepts(key))
            return CommandPlan.skip();
        return apply(args, RedisSlot.of(key));
    }
    private CommandPlan splitKeys(List<byte[]> args) {
        if (args.size() < 2)
            return CommandPlan.skip();
        List<CommandPlan.PlannedCommand> result = new ArrayList<>();
        for (int i = 1; i < args.size(); i++)
            if (filter.accepts(args.get(i)) && !internalCheckpoint(args.get(i))) {
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
            if (!filter.accepts(key) || internalCheckpoint(key))
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
    private static boolean internalCheckpoint(byte[] key) {
        if (key.length < INTERNAL_CHECKPOINT.length)
            return false;
        for (int i = 0; i < INTERNAL_CHECKPOINT.length; i++)
            if (key[i] != INTERNAL_CHECKPOINT[i])
                return false;
        return true;
    }
}
