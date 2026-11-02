package io.github.susongyan.redisops.platform.domain.sync;
import java.time.Instant;

/**
 * One live sync_runtime lease as shown on the worker page. {@code leaseOwner} is the process-unique
 * {@code instance-id-UUID}; the stable instance name is its prefix before the trailing UUID.
 */
public record SyncWorkerRuntimeRow(long taskId, String taskNo, String status, String leaseOwner, String phase,
        Long spoolBytes, Instant leaseUntil, Instant updatedAt, String assignedWorker) {
    public String stableInstance() {
        return leaseOwner == null
                ? null
                : leaseOwner.replaceAll("-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "");
    }
}
