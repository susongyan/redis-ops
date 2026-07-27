package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncRuntime;

import java.time.Duration;

/**
 * One task-local replication execution. Implementations own Redis connections and spool resources, while the manager
 * owns process-level concurrency and the MySQL runtime lease.
 */
public interface SyncTaskRunner extends AutoCloseable {

    /**
     * Validate and allocate task-local resources without modifying the target Redis.
     */
    void prepare();

    /**
     * Supplies the fencing generation after the MySQL runtime lease has been claimed.
     */
    default void leaseAcquired(SyncRuntime runtime) {
        // Optional for runners that do not write target data.
    }

    /**
     * Extends the process-local lease safety deadline after MySQL confirms a renewal.
     */
    default void leaseRenewed(Duration duration) {
        // Optional for runners that do not write target data.
    }

    /**
     * Irreversibly removes this runner's local write authority.
     */
    default void revokeLease() {
        // Optional for runners that do not write target data.
    }

    /**
     * Fails if the local safety deadline elapsed, including after a long JVM pause.
     */
    default void assertLeaseValid() {
        // Optional for runners that do not write target data.
    }

    /**
     * Start source ingestion and target application after the target preparation has completed.
     */
    void start();

    /**
     * Stop target application only after all in-flight batches have committed their checkpoints.
     */
    void pause();

    /**
     * Resume from the target checkpoint and local spool.
     */
    void resume();

    /**
     * Drain every source channel to its final offset and stop normally.
     */
    void finish();

    /**
     * Stop immediately without claiming that the target is complete.
     */
    void cancel();

    void updateLimits(SyncTask task);

    String phase();

    long spoolBytes();

    @Override
    void close();
}
