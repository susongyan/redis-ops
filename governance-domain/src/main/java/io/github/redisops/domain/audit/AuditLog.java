package io.github.redisops.domain.audit;

import java.time.Instant;

public record AuditLog(
        Long id,
        String operator,
        String action,
        String resourceType,
        String resourceId,
        String result,
        String requestId,
        String requestDigest,
        Instant createdAt) {
}
