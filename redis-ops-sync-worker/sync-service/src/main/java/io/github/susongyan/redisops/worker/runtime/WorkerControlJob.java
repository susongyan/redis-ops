package io.github.susongyan.redisops.worker.runtime;

/** Worker-owned projection of a leased asynchronous control job. */
public record WorkerControlJob(long id, String type, long taskId, String leaseOwner) {
}
