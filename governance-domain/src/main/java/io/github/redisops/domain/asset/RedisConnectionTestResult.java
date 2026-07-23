package io.github.redisops.domain.asset;

public record RedisConnectionTestResult(
        boolean reachable,
        ClusterMode mode,
        int discoveredNodeCount,
        long elapsedMillis,
        String message) {
}
