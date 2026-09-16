package io.github.susongyan.redisops.platform.domain.operation;

import java.time.Instant;

public record OperationCommand(Long id, String commandName, int commandVersion, String category, String accessMode,
        String riskLevel, boolean enabled, String parameterSchemaJson, int keyPosition, String routingPolicy,
        String approvalPolicy, int maxValueBytes, String allowedDataTypesJson, String missingKeyPolicy,
        boolean blockedByDefault, String changeReason, String updatedBy, long version, Instant createdAt,
        Instant updatedAt, String updatedBySnapshot) {
    public OperationCommand(Long id, String commandName, int commandVersion, String category, String accessMode,
            String riskLevel, boolean enabled, String parameterSchemaJson, int keyPosition, String routingPolicy,
            String approvalPolicy, int maxValueBytes, String allowedDataTypesJson, String missingKeyPolicy,
            boolean blockedByDefault, String changeReason, String updatedBy, long version, Instant createdAt,
            Instant updatedAt) {
        this(id, commandName, commandVersion, category, accessMode, riskLevel, enabled, parameterSchemaJson,
                keyPosition, routingPolicy, approvalPolicy, maxValueBytes, allowedDataTypesJson, missingKeyPolicy,
                blockedByDefault, changeReason, updatedBy, version, createdAt, updatedAt, null);
    }
}
