package io.github.susongyan.redisops.platform.domain.distribution;

import java.time.Instant;

public record DistributionTask(long id, long clusterId, DistributionSpec spec, String status, String reason,
        long version, long observed, int completedShards, int totalShards, long elapsedMillis,
        Instant createdAt, Instant updatedAt, boolean capacityReached) {
}
