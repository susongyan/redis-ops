package io.github.susongyan.redisops.platform.domain.asset;

public record RedisConnectionTestResult(
        boolean reachable,
        ClusterMode mode,
        int discoveredNodeCount,
        long elapsedMillis,
        String message) {
}
