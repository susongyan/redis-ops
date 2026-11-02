package io.github.susongyan.redisops.worker.runtime;

import java.time.Duration;
import java.util.Optional;

/** Worker boundary for claiming and completing sync control jobs. */
public interface WorkerControlJobPort {
    /**
     * Claims an unrouted control job (SYNC_PRECHECK/SYNC_START). {@code instanceName} is the
     * stable worker identity used for task assignment; a task assigned to another worker is
     * only claimable once that worker's registry heartbeat is older than {@code staleSeconds}
     * (or its registry row is gone), which is the failover escape hatch.
     */
    Optional<WorkerControlJob> claim(String type, String leaseOwner, Duration leaseDuration,
            String instanceName, long staleSeconds);

    Optional<WorkerControlJob> claimForRuntime(String type, String leaseOwner, String runtimeOwner,
            Duration leaseDuration, boolean allowExpiredRuntime);

    void complete(long jobId, String leaseOwner);

    boolean retryOrFail(long jobId, String leaseOwner, String error);
}
