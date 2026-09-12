package io.github.redisops.domain.asset;

import java.time.Instant;

public record DiscoveryRun(Long id, long clusterId, String status, Instant startedAt,
        Instant finishedAt, Integer nodeCount, String errorMessage) {
}
