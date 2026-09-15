package io.github.susongyan.redisops.platform.domain.asset;

public interface RedisConnectionTestPort {
    RedisConnectionTestResult test(ClusterMode mode, String endpoint, String username, char[] password);
}
