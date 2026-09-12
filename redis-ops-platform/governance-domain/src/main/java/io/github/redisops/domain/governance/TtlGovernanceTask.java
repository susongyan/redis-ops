package io.github.redisops.domain.governance;

import java.time.Instant;

public record TtlGovernanceTask(Long id, String taskNo, long clusterId, int databaseNo, String includePattern,
        long targetTtlSeconds, int scanRatePerSecond, long maxKeys, TtlGovernanceStatus status,
        TtlApprovalStatus approvalStatus, long version, Instant createdAt, Instant updatedAt) {
}
