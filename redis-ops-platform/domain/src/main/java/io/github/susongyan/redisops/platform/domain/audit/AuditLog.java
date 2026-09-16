package io.github.susongyan.redisops.platform.domain.audit;

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
        Instant createdAt, String operatorSnapshot, String detailsJson) {
    public AuditLog(Long id, String operator, String action, String resourceType, String resourceId,
            String result, String requestId, String requestDigest, Instant createdAt, String operatorSnapshot) {
        this(id, operator, action, resourceType, resourceId, result, requestId, requestDigest, createdAt,
                operatorSnapshot, null);
    }
    public AuditLog(
            Long id,
            String operator,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String requestId,
            String requestDigest,
            Instant createdAt) {
        this(id, operator, action, resourceType, resourceId, result, requestId, requestDigest, createdAt, null);
    }
}
