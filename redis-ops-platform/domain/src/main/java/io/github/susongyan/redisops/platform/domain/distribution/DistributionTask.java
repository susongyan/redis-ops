package io.github.susongyan.redisops.platform.domain.distribution;

import java.time.Instant;

public record DistributionTask(long id, long clusterId, DistributionSpec spec, String status, String reason,
        long version, long observed, long estimatedTotal, int completedShards, int totalShards, long elapsedMillis,
        Instant createdAt, Instant updatedAt, boolean capacityReached,
        java.util.List<DistributionScanPort.Shard> scanNodes) {
    public DistributionTask(long id, long clusterId, DistributionSpec spec, String status, String reason,
            long version, long observed, int completedShards, int totalShards, long elapsedMillis,
            Instant createdAt, Instant updatedAt, boolean capacityReached) {
        this(id, clusterId, spec, status, reason, version, observed, 0, completedShards, totalShards, elapsedMillis,
                createdAt, updatedAt, capacityReached, java.util.List.of());
    }
    public DistributionTask withScanNodes(java.util.List<DistributionScanPort.Shard> nodes) {
        return new DistributionTask(id, clusterId, spec, status, reason, version, observed, estimatedTotal,
                completedShards, totalShards, elapsedMillis, createdAt, updatedAt, capacityReached,
                java.util.List.copyOf(nodes));
    }
    // progress denominator sampled once at scan start (sum of per-shard DBSIZE); 0 = unknown,
    // filled from the checkpoint only by the detail endpoint, never by list queries
    public DistributionTask withEstimatedTotal(long value) {
        return new DistributionTask(id, clusterId, spec, status, reason, version, observed, value, completedShards,
                totalShards, elapsedMillis, createdAt, updatedAt, capacityReached, scanNodes);
    }
}
