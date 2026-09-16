package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.ReplicationCommand;
import io.github.susongyan.redisops.worker.protocol.SourceTransactionAssembler;
import java.util.concurrent.Semaphore;

/** Encoded-byte budget, in addition to queue element count; one per source channel. */
final class ReplicationQueueBudget {
    private final Semaphore bytes = new Semaphore(32 * 1024 * 1024);
    void acquire(ReplicationCommand command) throws InterruptedException {
        long size = command.encodedBytes();
        if (size > SourceTransactionAssembler.MAX_BYTES)
            throw new SyncBlockedException("BLOCKED_TRANSACTION_LIMIT", "replication command exceeds frame budget");
        bytes.acquire(Math.toIntExact(size));
    }
    void release(ReplicationCommand command) {
        bytes.release(Math.toIntExact(command.encodedBytes()));
    }
}
