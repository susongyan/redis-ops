package io.github.redisops.domain.asset;

public interface RedisConnectionProfileProvider {
    RedisConnectionProfile get(long clusterId);
}
