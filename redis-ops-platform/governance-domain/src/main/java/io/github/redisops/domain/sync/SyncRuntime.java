package io.github.redisops.domain.sync;

import java.time.Instant;

public record SyncRuntime(long taskId, String runtimeId, String leaseOwner, Instant leaseUntil, long fencingGeneration,
        String phase, Instant heartbeatAt, long spoolBytes, Long targetFenceGeneration, Instant fencePublishedAt,
        int takeoverCount, String recoveryAction, String lastError, Instant startedAt, Instant updatedAt) {
}
