package io.github.susongyan.redisops.platform.domain.validation;

import java.util.*;

/** Redis data access used by validation. Implementations must not log key or value bytes. */
public interface RedisValidationPort {
    ScanPage scan(long clusterId, int database, String cursor, int count);
    List<ScanShard> scanShards(long clusterId, int database);
    ScanPage scan(long clusterId, int database, String shardId, String cursor, int count);
    long countKeys(long clusterId, int database);
    Optional<ValidationValue> inspect(long clusterId, int database, byte[] key, ValidationTask task);
    /** Pipelined per-page counterpart of {@link #inspect}; the result is positionally aligned with {@code keys}. */
    List<Optional<ValidationValue>> inspectBatch(long clusterId, int database, List<byte[]> keys,
            ValidationTask task);
    TtlApplyResult applyTtlIfUnchanged(long clusterId, int database, byte[] key, long expectedTtlSeconds,
            long targetTtlSeconds);
    boolean unlinkIfPresent(long clusterId, int database, byte[] key);
    /**
     * Pipelined per-page counterpart of {@link #applyTtlIfUnchanged}; the result is positionally aligned
     * with {@code keys}. One profile lookup and pipeline per page instead of per key — the per-key form
     * spends its budget on cluster-profile DB roundtrips, not Redis.
     */
    List<TtlApplyResult> applyTtlIfUnchangedBatch(long clusterId, int database, List<byte[]> keys,
            long expectedTtlSeconds, long targetTtlSeconds);
    /**
     * Pipelined per-page counterpart of {@link #unlinkIfPresent}; true per position only when the key was
     * present and unlinked (a key that vanished reads as false).
     */
    List<Boolean> unlinkIfPresentBatch(long clusterId, int database, List<byte[]> keys);

    record ValidationKey(byte[] bytes) {
    }

    record ScanPage(String nextCursor, List<ValidationKey> keys) {
    }

    record ScanShard(String id, String endpoint) {
    }

    record ValidationValue(String type, long size, long ttlSeconds, String digest, String comparisonLevel,
            String degradedReason) {
        public boolean degraded() {
            return degradedReason != null;
        }
    }

    record TtlApplyResult(long observedTtlSeconds, boolean applied, boolean skipped) {
    }
}
