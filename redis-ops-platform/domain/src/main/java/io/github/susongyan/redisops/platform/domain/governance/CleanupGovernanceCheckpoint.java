package io.github.susongyan.redisops.platform.domain.governance;

import java.time.Instant;

public record CleanupGovernanceCheckpoint(long runId, String shardId, String cursor, long scannedKeys,
        TtlGovernanceStatus status, Instant updatedAt) {
}
