package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.worker.domain.WorkerClusterView;
import io.github.redisops.sync.worker.domain.WorkerRedisNode;

import java.util.List;

/** Worker-only topology discovery boundary. */
public interface WorkerTopologyPort {
    List<WorkerRedisNode> discover(WorkerClusterView cluster);
}
