package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.worker.domain.WorkerClusterView;

/** Worker-only, read-only access to Redis asset metadata. */
public interface WorkerAssetReadPort {
    WorkerClusterView get(long clusterId);
}
