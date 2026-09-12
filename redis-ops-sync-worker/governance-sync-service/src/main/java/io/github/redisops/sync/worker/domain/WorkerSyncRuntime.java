package io.github.redisops.sync.worker.domain;

import java.time.Instant;

/** Worker-owned lease and fencing projection for one synchronization task. */
public record WorkerSyncRuntime(long taskId, String runtimeId, String leaseOwner, Instant leaseUntil,
        long fencingGeneration, String phase, Instant heartbeatAt, long spoolBytes, Long targetFenceGeneration,
        Instant fencePublishedAt, int takeoverCount, String recoveryAction, String lastError, Instant startedAt,
        Instant updatedAt) {
}
