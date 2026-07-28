package io.github.redisops.domain.sync;
import java.time.Instant;
public record SyncTask(Long id, String taskNo, Long relationId, long sourceClusterId, long targetClusterId,
        SyncPurpose purpose, SyncMode syncMode, SyncTaskStatus status, String toolType,
        int sourceDb, int targetDb, String includePatternsJson, String excludePatternsJson,
        String commandPolicyJson,
        long rateLimitOps, long bandwidthLimitBytesPerSecond, long spoolLimitBytes,
        int fullApplyConcurrency, int fullApplyPipelineSize,
        String desiredAction, boolean writeFenced, String writeFenceNote, String blockedReason,
        String fullSyncEpoch, Long lastRpoSeconds, String lastError, long version,
        Instant createdAt, Instant updatedAt, Instant finishedAt) {
    public static final long DEFAULT_RATE_LIMIT_OPS = 50_000;
    public static final long DEFAULT_BANDWIDTH_LIMIT = 100L * 1024 * 1024;
    public static final long DEFAULT_SPOOL_LIMIT = 50L * 1024 * 1024 * 1024;
    public static final int DEFAULT_FULL_APPLY_CONCURRENCY = 4;
    public static final int DEFAULT_FULL_APPLY_PIPELINE_SIZE = 100;

    public SyncTask(Long id, String taskNo, Long relationId, long sourceClusterId, long targetClusterId,
            SyncPurpose purpose, SyncMode syncMode, SyncTaskStatus status, String toolType,
            int sourceDb, int targetDb, String includePatternsJson, String excludePatternsJson,
            long rateLimitOps, long bandwidthLimitBytesPerSecond, long spoolLimitBytes,
            int fullApplyConcurrency, int fullApplyPipelineSize,
            String desiredAction, boolean writeFenced, String writeFenceNote, String blockedReason,
            String fullSyncEpoch, Long lastRpoSeconds, String lastError, long version,
            Instant createdAt, Instant updatedAt, Instant finishedAt) {
        this(id, taskNo, relationId, sourceClusterId, targetClusterId, purpose, syncMode, status, toolType,
                sourceDb, targetDb, includePatternsJson, excludePatternsJson,
                "{\"allowDestructiveCommands\":false,\"allowSafeSplit\":true,"
                        + "\"additionalBlockedCommands\":[],\"policyVersion\":\"v1\"}",
                rateLimitOps, bandwidthLimitBytesPerSecond, spoolLimitBytes, fullApplyConcurrency,
                fullApplyPipelineSize, desiredAction, writeFenced, writeFenceNote, blockedReason, fullSyncEpoch,
                lastRpoSeconds, lastError, version, createdAt, updatedAt, finishedAt);
    }

    public SyncTask withStatus(SyncTaskStatus target, String desired, String blocked, String error, Long rpo) {
        Instant now = Instant.now();
        return new SyncTask(id, taskNo, relationId, sourceClusterId, targetClusterId, purpose, syncMode, target,
                toolType,
                sourceDb, targetDb, includePatternsJson, excludePatternsJson, commandPolicyJson, rateLimitOps,
                bandwidthLimitBytesPerSecond,
                spoolLimitBytes, fullApplyConcurrency, fullApplyPipelineSize, desired, writeFenced,
                writeFenceNote, blocked, fullSyncEpoch,
                rpo == null ? lastRpoSeconds : rpo, error, version, createdAt, now, target.terminal() ? now : null);
    }
}
