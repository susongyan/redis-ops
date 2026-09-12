package io.github.redisops.worker.persistence;

import io.github.redisops.worker.domain.WorkerClusterView;
import io.github.redisops.worker.domain.WorkerRedisNode;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Read-only Worker asset and topology adapter. */
@Repository
public class WorkerMyBatisAssetReader implements WorkerAssetReadPort, WorkerTopologyPort {
    private final WorkerAssetMapper mapper;

    public WorkerMyBatisAssetReader(WorkerAssetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkerClusterView get(long clusterId) {
        WorkerClusterView cluster = mapper.findCluster(clusterId);
        if (cluster == null)
            throw new IllegalArgumentException("Redis cluster not found: " + clusterId);
        return cluster;
    }

    @Override
    public List<WorkerRedisNode> discover(WorkerClusterView cluster) {
        return mapper.findNodes(cluster.id());
    }
}
