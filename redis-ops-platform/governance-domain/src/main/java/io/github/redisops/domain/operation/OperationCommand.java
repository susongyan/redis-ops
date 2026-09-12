package io.github.redisops.domain.operation;

import java.time.Instant;

public record OperationCommand(Long id, String commandName, int commandVersion, String category, String accessMode,
        String riskLevel, boolean enabled, String parameterSchemaJson, int keyPosition, String routingPolicy,
        String approvalPolicy, int maxValueBytes, String allowedDataTypesJson, String missingKeyPolicy,
        boolean blockedByDefault, String changeReason, String updatedBy, long version, Instant createdAt,
        Instant updatedAt) {
}
