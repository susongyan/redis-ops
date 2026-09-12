package io.github.redisops.worker.domain;

import java.time.Instant;

/** Observational full-sync progress owned by the Worker persistence boundary. */
public record WorkerSyncFullProgress(long taskId, String fullSyncEpoch, String channelId, int lane, String stage,
        Long totalBytes, long receivedBytes, long parsedBytes, Long totalKeys, long parsedKeys, long appliedKeys,
        long appliedBytes, String status, Instant startedAt, Instant updatedAt) {
}
