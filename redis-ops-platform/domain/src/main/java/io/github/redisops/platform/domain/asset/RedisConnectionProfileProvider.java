package io.github.redisops.platform.domain.asset;

public interface RedisConnectionProfileProvider {
    RedisConnectionProfile get(long clusterId);
}
