package io.github.redisops.worker.runtime;

public interface WorkerRedisConnectionProfilePort {
    WorkerRedisConnectionProfile get(long clusterId);
}
