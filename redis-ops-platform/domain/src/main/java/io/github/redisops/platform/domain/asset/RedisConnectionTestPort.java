package io.github.redisops.platform.domain.asset;

public interface RedisConnectionTestPort {
    RedisConnectionTestResult test(ClusterMode mode, String endpoint, String username, char[] password);
}
