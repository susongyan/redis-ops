package io.github.redisops.sync.engine;

public interface WorkerRedisConnectionProfilePort {
    WorkerRedisConnectionProfile get(long clusterId);
}
