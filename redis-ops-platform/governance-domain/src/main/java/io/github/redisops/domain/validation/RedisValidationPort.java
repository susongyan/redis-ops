package io.github.redisops.domain.validation;

import java.util.*;

/** Redis data access used by validation. Implementations must not log key or value bytes. */
public interface RedisValidationPort {
    ScanPage scan(long clusterId, int database, String cursor, int count);
    List<ScanShard> scanShards(long clusterId, int database);
    ScanPage scan(long clusterId, int database, String shardId, String cursor, int count);
    long countKeys(long clusterId, int database);
    Optional<ValidationValue> inspect(long clusterId, int database, byte[] key, ValidationTask task);
    TtlApplyResult applyTtlIfUnchanged(long clusterId, int database, byte[] key, long expectedTtlSeconds,
            long targetTtlSeconds);
    boolean unlinkIfPresent(long clusterId, int database, byte[] key);

    record ValidationKey(byte[] bytes) {
    }

    record ScanPage(String nextCursor, List<ValidationKey> keys) {
    }

    record ScanShard(String id) {
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
