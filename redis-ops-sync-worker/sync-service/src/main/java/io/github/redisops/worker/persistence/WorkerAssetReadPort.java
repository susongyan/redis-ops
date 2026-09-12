package io.github.redisops.worker.persistence;

import io.github.redisops.worker.domain.WorkerClusterView;

/** Worker-only, read-only access to Redis asset metadata. */
public interface WorkerAssetReadPort {
    WorkerClusterView get(long clusterId);
}
