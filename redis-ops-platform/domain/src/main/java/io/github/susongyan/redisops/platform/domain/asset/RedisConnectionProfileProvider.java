package io.github.susongyan.redisops.platform.domain.asset;

public interface RedisConnectionProfileProvider {
    RedisConnectionProfile get(long clusterId);
}
