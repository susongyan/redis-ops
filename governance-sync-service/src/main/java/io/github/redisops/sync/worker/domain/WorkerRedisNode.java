package io.github.redisops.sync.worker.domain;

/** Read-only Worker topology node projection. */
public record WorkerRedisNode(String host, int port, String role) {
}
