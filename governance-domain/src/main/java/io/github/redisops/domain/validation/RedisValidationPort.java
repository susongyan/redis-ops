package io.github.redisops.domain.validation;

import java.util.*;

/** Redis data access used by validation. Implementations must not log key or value bytes. */
public interface RedisValidationPort {
    ScanPage scan(long clusterId, int database, String cursor, int count);
    Optional<ValidationValue> inspect(long clusterId, int database, byte[] key, ValidationTask task);

    record ValidationKey(byte[] bytes) {
    }

    record ScanPage(String nextCursor, List<ValidationKey> keys) {
    }

    record ValidationValue(String type, long size, long ttlSeconds, String digest, String comparisonLevel,
            String degradedReason) {
        public boolean degraded() {
            return degradedReason != null;
        }
    }
}
