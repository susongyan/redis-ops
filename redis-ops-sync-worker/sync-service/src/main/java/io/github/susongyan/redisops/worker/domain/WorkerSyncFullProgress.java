package io.github.susongyan.redisops.worker.domain;

import java.time.Instant;

/**
 * Observational full-sync progress owned by the Worker persistence boundary. The split_* fields
 * are channel-level split-path statistics (design doc §9): splitKeys/splitBytes count keys
 * rebuilt element-by-element with their wire payload size, splitSkipped* the ones dropped with a
 * SPLIT_KEY_SKIPPED event. parsedKeys/appliedKeys keep their RESTORE-path meaning.
 */
public record WorkerSyncFullProgress(long taskId, String fullSyncEpoch, String channelId, int lane, String stage,
        Long totalBytes, long receivedBytes, long parsedBytes, Long totalKeys, long parsedKeys, long appliedKeys,
        long appliedBytes, long splitKeys, long splitBytes, long splitSkippedKeys, long splitSkippedBytes,
        String status, Instant startedAt, Instant updatedAt) {
}
