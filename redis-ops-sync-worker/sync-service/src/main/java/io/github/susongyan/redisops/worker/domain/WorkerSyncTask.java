package io.github.susongyan.redisops.worker.domain;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;

import java.time.Instant;
import java.util.Locale;

/** Worker-owned projection of the synchronization task control row. */
public record WorkerSyncTask(Long id, String taskNo, Long relationId, long sourceClusterId, long targetClusterId,
        String purpose, String syncMode, SyncContractStatus status, String toolType,
        int sourceDb, int targetDb, String includePatternsJson, String excludePatternsJson,
        String commandPolicyJson, String resetMode, long rateLimitOps, long bandwidthLimitBytesPerSecond,
        long spoolLimitBytes, int fullApplyConcurrency, int fullApplyPipelineSize, String desiredAction,
        boolean writeFenced, String writeFenceNote, String blockedReason, String fullSyncEpoch, Long lastRpoSeconds,
        String lastError, long version, Instant createdAt, Instant updatedAt, Instant finishedAt) {
    public static final String RESET_MODE_PRESERVE = "PRESERVE";
    public static final String RESET_MODE_FLUSH = "FLUSH";

    public WorkerSyncTask {
        resetMode = normalizeResetMode(resetMode);
    }

    /** Preserved targets are never flushed; the operator owns pre-existing target data. */
    public boolean flushTarget() {
        return RESET_MODE_FLUSH.equals(resetMode);
    }

    private static String normalizeResetMode(String value) {
        if (value == null || value.isBlank())
            return RESET_MODE_PRESERVE;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!RESET_MODE_PRESERVE.equals(normalized) && !RESET_MODE_FLUSH.equals(normalized))
            throw new IllegalArgumentException("invalid sync task reset mode: " + value);
        return normalized;
    }
}
