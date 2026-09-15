package io.github.susongyan.redisops.worker.runtime;

public interface WorkerRedisConnectionProfilePort {
    WorkerRedisConnectionProfile get(long clusterId);
}
