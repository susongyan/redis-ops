package io.github.susongyan.redisops.worker.runtime;

@FunctionalInterface
interface FullRestoreProgressListener {
    void applied(int lane, long keys, long bytes);
}
