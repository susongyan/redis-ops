package io.github.redisops.domain.governance;

import java.time.Instant;

public record TtlGovernanceRun(Long id, long taskId, String runNo, TtlGovernanceStatus status, long plannedKeys,
        long scannedKeys, long candidateKeys, long appliedKeys, long skippedKeys, long failedKeys, Instant startedAt,
        Instant completedAt, String errorCode) {
}
