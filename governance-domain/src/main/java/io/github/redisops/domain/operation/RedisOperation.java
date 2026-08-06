package io.github.redisops.domain.operation;

import java.time.Instant;

public record RedisOperation(Long id, String operationNo, long clusterId, int databaseNo, String commandName,
        String argumentsJson, String argumentsDigest, String accessMode, String riskLevel, String status,
        String previewJson, String approvalNote, String operatorName, String approverName, String resultJson,
        long version, Instant createdAt, Instant updatedAt) {
}
