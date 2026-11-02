package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.ReplicationCommand;
import java.util.concurrent.Semaphore;

/**
 * Encoded-byte budget, in addition to queue element count; one per source channel. Applied to
 * every enqueued command, transactional or not: without it a slow apply fills the count-only
 * queue with multi-megabyte payloads and the heap dies. A single command larger than the whole
 * budget reserves all of it, so it passes once the queue drains instead of being rejected.
 */
final class ReplicationQueueBudget {
    private static final long LIMIT = 32L * 1024 * 1024;
    private final Semaphore bytes = new Semaphore(Math.toIntExact(LIMIT));

    void acquire(ReplicationCommand command) throws InterruptedException {
        bytes.acquire(permits(command));
    }

    void release(ReplicationCommand command) {
        bytes.release(permits(command));
    }

    private int permits(ReplicationCommand command) {
        return (int) Math.min(command.encodedBytes(), LIMIT);
    }
}
