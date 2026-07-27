package io.github.redisops.sync.engine;

import io.github.redisops.sync.protocol.RedisSlot;

import java.nio.charset.StandardCharsets;

/**
 * Produces platform-internal keys that are guaranteed to share a Redis Cluster slot with the business command being
 * committed. Tags are generated once and are stable for the lifetime of the process.
 */
final class ClusterSlotKeyspace {
    private static final int SLOT_COUNT = 16384;
    private static final String[] TAGS = buildTags();

    private ClusterSlotKeyspace() {
    }

    static byte[] checkpoint(long taskId, String channel, int slot) {
        return key("__redis_ops_sync_ckpt__", taskId, channel, slot);
    }

    static byte[] cursor(long taskId, String channel, int slot) {
        return key("__redis_ops_sync_cursor__", taskId, channel, slot);
    }

    static byte[] fence(long taskId, String channel, int slot) {
        return key("__redis_ops_sync_fence__", taskId, channel, slot);
    }

    static byte[] heartbeat(long taskId, String channel, int slot) {
        return key("__redis_ops_sync_hb__", taskId, channel, slot);
    }

    static byte[] fullProgress(long taskId, String channel, int slot, int lane) {
        return (prefix("__redis_ops_sync_full_progress__", taskId, channel, slot) + ":" + lane)
                .getBytes(StandardCharsets.US_ASCII);
    }

    static String tag(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT)
            throw new IllegalArgumentException("Redis slot must be between 0 and 16383");
        return TAGS[slot];
    }

    private static byte[] key(String prefix, long taskId, String channel, int slot) {
        return prefix(prefix, taskId, channel, slot).getBytes(StandardCharsets.US_ASCII);
    }

    private static String prefix(String prefix, long taskId, String channel, int slot) {
        if (channel == null || channel.isBlank())
            throw new IllegalArgumentException("sync channel is required");
        return prefix + ":{" + tag(slot) + "}:" + taskId + ":" + channel;
    }

    private static String[] buildTags() {
        String[] tags = new String[SLOT_COUNT];
        int remaining = SLOT_COUNT;
        for (long candidate = 0; remaining > 0; candidate++) {
            String tag = "rs" + Long.toString(candidate, 36);
            int slot = RedisSlot.of(tag);
            if (tags[slot] == null) {
                tags[slot] = tag;
                remaining--;
            }
        }
        return tags;
    }
}
