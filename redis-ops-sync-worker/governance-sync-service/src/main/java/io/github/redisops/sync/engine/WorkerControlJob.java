package io.github.redisops.sync.engine;

/** Worker-owned projection of a leased asynchronous control job. */
public record WorkerControlJob(long id, String type, long taskId, String leaseOwner) {
}
