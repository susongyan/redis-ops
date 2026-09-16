package io.github.susongyan.redisops.platform.domain.operation;

import java.time.Instant;

public record RedisOperation(Long id, String operationNo, long clusterId, int databaseNo, String commandName,
        String argumentsJson, String argumentsDigest, String accessMode, String riskLevel, String status,
        String previewJson, String approvalNote, String operatorName, String approverName, String resultJson,
        long version, Instant createdAt, Instant updatedAt, String operatorSnapshot, String approverSnapshot,
        String executorName, String executorSnapshot) {
    public RedisOperation(Long id, String operationNo, long clusterId, int databaseNo, String commandName,
            String argumentsJson, String argumentsDigest, String accessMode, String riskLevel, String status,
            String previewJson, String approvalNote, String operatorName, String approverName, String resultJson,
            long version, Instant createdAt, Instant updatedAt) {
        this(id, operationNo, clusterId, databaseNo, commandName, argumentsJson, argumentsDigest, accessMode, riskLevel,
                status, previewJson, approvalNote, operatorName, approverName, resultJson, version, createdAt,
                updatedAt, null, null, null, null);
    }
}
