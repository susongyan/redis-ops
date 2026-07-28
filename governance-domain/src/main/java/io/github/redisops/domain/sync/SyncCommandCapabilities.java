package io.github.redisops.domain.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class SyncCommandCapabilities {
    private static final Set<String> SINGLE_KEY = Set.of(
            "SET", "SETEX", "PSETEX", "SETNX", "APPEND", "GETSET", "INCR", "DECR", "INCRBY", "DECRBY",
            "INCRBYFLOAT", "HSET", "HMSET", "HSETNX", "HDEL", "HINCRBY", "HINCRBYFLOAT", "LPUSH", "RPUSH",
            "LPOP", "RPOP", "LSET", "LTRIM", "LREM", "LINSERT", "SADD", "SREM", "ZADD", "ZREM", "ZINCRBY",
            "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZREMRANGEBYLEX", "EXPIRE", "PEXPIRE", "EXPIREAT",
            "PEXPIREAT", "PERSIST", "SETBIT", "BITFIELD", "PFADD", "XADD", "XACK", "XDEL", "XTRIM", "XSETID",
            "XGROUP", "RESTORE");
    private static final Set<String> SAFE_SPLIT = Set.of("MSET", "DEL", "UNLINK");
    private static final Set<String> HARD_BLOCKED = Set.of(
            "MSETNX", "RENAME", "RENAMENX", "BITOP", "SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE",
            "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE", "SMOVE", "LMOVE", "RPOPLPUSH", "BRPOPLPUSH", "COPY",
            "SORT", "EVAL", "EVALSHA", "FCALL", "FCALL_RO", "MULTI", "EXEC", "DISCARD");
    private static final Set<String> DESTRUCTIVE = Set.of("FLUSHDB", "FLUSHALL");
    private static final Set<String> SKIPPED = Set.of("SELECT", "PING", "REPLCONF");

    private SyncCommandCapabilities() {
    }

    public static boolean singleKey(String command) {
        return SINGLE_KEY.contains(command);
    }

    public static boolean safeSplit(String command) {
        return SAFE_SPLIT.contains(command);
    }

    public static boolean hardBlocked(String command) {
        return HARD_BLOCKED.contains(command);
    }

    public static boolean destructive(String command) {
        return DESTRUCTIVE.contains(command);
    }

    public static boolean skipped(String command) {
        return SKIPPED.contains(command);
    }

    public static SyncCommandCapability classify(
            String command, boolean clusterTarget, SyncCommandPolicy policy) {
        if (hardBlocked(command)) {
            return new SyncCommandCapability(
                    command, "HARD_BLOCKED", "无法保证等价转换或原子性", false, true);
        }
        if (destructive(command) && clusterTarget) {
            return new SyncCommandCapability(
                    command, "HARD_BLOCKED", "Cluster 目标不支持在增量流中执行全节点清空", false, true);
        }
        if (skipped(command)) {
            return new SyncCommandCapability(command, "IGNORED", "复制协议控制命令，不写入目标", false, false);
        }
        if (policy.additionallyBlocks(command)) {
            return new SyncCommandCapability(
                    command, "POLICY_BLOCKED", "任务策略显式屏蔽", true, true);
        }
        if (destructive(command)) {
            boolean blocked = !policy.allowDestructiveCommands();
            return new SyncCommandCapability(command, blocked ? "POLICY_BLOCKED" : "SUPPORTED",
                    "危险命令，必须在任务策略中显式允许", true, blocked);
        }
        if (safeSplit(command)) {
            boolean blocked = !policy.allowSafeSplit();
            return new SyncCommandCapability(
                    command,
                    blocked ? "POLICY_BLOCKED" : "TRANSFORMABLE",
                    "可按 Key/Slot 安全拆分，但不保留跨 Key 原子性",
                    true,
                    blocked);
        }
        if (singleKey(command)) {
            return new SyncCommandCapability(command, "SUPPORTED", "直接同步", false, false);
        }
        return new SyncCommandCapability(
                command, "UNKNOWN_BLOCKED", "未知命令采用失败关闭策略", false, true);
    }

    public static List<SyncCommandCapability> all(boolean clusterTarget, SyncCommandPolicy policy) {
        Set<String> known = new java.util.HashSet<>();
        known.addAll(SINGLE_KEY);
        known.addAll(SAFE_SPLIT);
        known.addAll(HARD_BLOCKED);
        known.addAll(DESTRUCTIVE);
        known.addAll(SKIPPED);
        known.addAll(policy.additionalBlockedCommands());
        List<SyncCommandCapability> result = new ArrayList<>();
        for (String command : known) {
            result.add(classify(command, clusterTarget, policy));
        }
        result.sort(Comparator.comparing(SyncCommandCapability::category)
                .thenComparing(SyncCommandCapability::command));
        return List.copyOf(result);
    }
}
