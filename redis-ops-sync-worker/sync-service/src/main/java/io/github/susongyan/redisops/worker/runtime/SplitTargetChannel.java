package io.github.susongyan.redisops.worker.runtime;

import java.io.IOException;
import java.util.List;

/**
 * Target-side plumbing of the split path (design doc §6/§7), one implementation per target shape.
 * The writer stays mode-free: every operation takes the key whose slot decides routing, which only
 * the Cluster implementation actually uses.
 */
interface SplitTargetChannel extends AutoCloseable {
    /**
     * Applies one WATCH-fenced MULTI/EXEC batch. {@code slotKey} is any key of the batch (they all
     * hash to the same slot by construction — the temp key); the Cluster implementation publishes
     * and watches that slot's fence.
     */
    void apply(byte[] slotKey, List<byte[][]> commands, TargetFence fence, LeaseGuard leaseGuard)
            throws IOException;

    long llen(byte[] key) throws IOException;

    long exists(byte[] key) throws IOException;

    long unlink(byte[] key) throws IOException;

    /** Live {@code proto-max-bulk-len} of the target (any node in Cluster mode). */
    long protoMaxBulkLen() throws IOException;

    /**
     * Drops the connection serving {@code slotKey}'s slot before the next attempt; the Cluster
     * implementation also refreshes the topology. Best effort — a failure here only delays the
     * error to the next retry.
     */
    void reopen(byte[] slotKey);

    /** Deterministic temp key name for {@code key} (§6.1); same key always maps to the same name. */
    byte[] tempKey(long taskId, String channel, byte[] key);

    /** Standalone closes its own session; the Cluster router is owned by the sync channel. */
    @Override
    void close();
}
