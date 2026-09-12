package io.github.redisops.worker.persistence;

import io.github.redisops.worker.domain.WorkerClusterView;
import io.github.redisops.worker.domain.WorkerRedisNode;

import java.util.List;

/** Worker-only topology discovery boundary. */
public interface WorkerTopologyPort {
    List<WorkerRedisNode> discover(WorkerClusterView cluster);
}
