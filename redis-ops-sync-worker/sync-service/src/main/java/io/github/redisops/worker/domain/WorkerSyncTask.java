package io.github.redisops.worker.domain;

import io.github.redisops.sync.contract.SyncContractStatus;

import java.time.Instant;

/** Worker-owned projection of the synchronization task control row. */
public record WorkerSyncTask(Long id, String taskNo, Long relationId, long sourceClusterId, long targetClusterId,
        String purpose, String syncMode, SyncContractStatus status, String toolType,
        int sourceDb, int targetDb, String includePatternsJson, String excludePatternsJson,
        String commandPolicyJson, long rateLimitOps, long bandwidthLimitBytesPerSecond, long spoolLimitBytes,
        int fullApplyConcurrency, int fullApplyPipelineSize, String desiredAction, boolean writeFenced,
        String writeFenceNote, String blockedReason, String fullSyncEpoch, Long lastRpoSeconds, String lastError,
        long version, Instant createdAt, Instant updatedAt, Instant finishedAt) {
}
