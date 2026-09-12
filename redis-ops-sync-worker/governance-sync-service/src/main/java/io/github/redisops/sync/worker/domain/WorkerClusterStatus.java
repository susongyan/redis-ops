package io.github.redisops.sync.worker.domain;

/** Worker-local asset availability state. */
public enum WorkerClusterStatus {
    ACTIVE, INACTIVE, UNREACHABLE
}
