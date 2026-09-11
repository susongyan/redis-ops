package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.worker.domain.WorkerClusterView;

/** Worker-only, read-only access to Redis asset metadata. */
public interface WorkerAssetReadPort {
    WorkerClusterView get(long clusterId);
}
