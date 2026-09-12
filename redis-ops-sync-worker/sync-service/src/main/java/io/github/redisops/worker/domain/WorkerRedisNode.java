package io.github.redisops.worker.domain;

/** Read-only Worker topology node projection. */
public record WorkerRedisNode(String host, int port, String role) {
}
