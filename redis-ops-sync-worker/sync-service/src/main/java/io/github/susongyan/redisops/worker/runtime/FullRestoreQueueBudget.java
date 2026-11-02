package io.github.susongyan.redisops.worker.runtime;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Encoded-byte budget for a full-restore queue, in addition to its element count. */
final class FullRestoreQueueBudget {
    private final long limit;
    private final Semaphore bytes;

    FullRestoreQueueBudget(long limit) {
        if (limit < 1024)
            throw new IllegalArgumentException("full restore queue budget must be at least 1024 bytes");
        this.limit = limit;
        this.bytes = new Semaphore(Math.toIntExact(limit));
    }

    /**
     * Permits one restore request occupies. An item larger than the whole budget reserves all of
     * it, so a huge key still passes once the queue drains instead of being rejected.
     */
    int permits(TargetCommandSession.RestoreRequest request) {
        return (int) Math.min(request.key().length + request.payload().length + 128L, limit);
    }

    boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        return bytes.tryAcquire(permits, timeout, unit);
    }

    void release(int permits) {
        bytes.release(permits);
    }
}
