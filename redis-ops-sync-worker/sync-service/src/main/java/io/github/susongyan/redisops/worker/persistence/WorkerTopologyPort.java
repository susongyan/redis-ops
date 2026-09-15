package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.worker.domain.WorkerClusterView;
import io.github.susongyan.redisops.worker.domain.WorkerRedisNode;

import java.util.List;

/** Worker-only topology discovery boundary. */
public interface WorkerTopologyPort {
    List<WorkerRedisNode> discover(WorkerClusterView cluster);
}
