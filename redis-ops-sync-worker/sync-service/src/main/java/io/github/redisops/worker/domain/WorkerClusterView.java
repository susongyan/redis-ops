package io.github.redisops.worker.domain;

import io.github.redisops.worker.runtime.WorkerClusterMode;

/** Read-only Worker projection of a Redis asset. */
public record WorkerClusterView(long id, WorkerClusterMode mode, String redisVersion, String endpoint,
        WorkerClusterStatus status) {
    public boolean active() {
        return status == WorkerClusterStatus.ACTIVE;
    }
}
