package io.github.redisops.sync.engine;

@FunctionalInterface
interface FullRestoreProgressListener {
    void applied(int lane, long keys, long bytes);
}
