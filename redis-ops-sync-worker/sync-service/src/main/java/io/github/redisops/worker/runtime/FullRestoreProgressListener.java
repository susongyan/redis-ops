package io.github.redisops.worker.runtime;

@FunctionalInterface
interface FullRestoreProgressListener {
    void applied(int lane, long keys, long bytes);
}
