package io.github.redisops.platform.domain.idempotency;

public record IdempotencyRecord(String operator, String key, String operation,
        String requestDigest, String status, String resourceId) {
}
