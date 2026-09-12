package io.github.redisops.domain.governance;

import java.time.Instant;

public record CleanupGovernanceTask(Long id, String taskNo, long clusterId, int databaseNo, String includePattern,
        long impactLimit, int scanRatePerSecond, TtlGovernanceStatus status, TtlApprovalStatus approvalStatus,
        String approvalNote, long version, Instant createdAt, Instant updatedAt) {
}
