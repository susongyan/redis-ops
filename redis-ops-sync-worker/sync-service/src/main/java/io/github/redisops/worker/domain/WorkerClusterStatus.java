package io.github.redisops.worker.domain;

/** Worker-local asset availability state. */
public enum WorkerClusterStatus {
    ACTIVE, INACTIVE, UNREACHABLE
}
