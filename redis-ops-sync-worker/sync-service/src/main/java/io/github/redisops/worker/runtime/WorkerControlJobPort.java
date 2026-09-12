package io.github.redisops.worker.runtime;

import java.time.Duration;
import java.util.Optional;

/** Worker boundary for claiming and completing sync control jobs. */
public interface WorkerControlJobPort {
    Optional<WorkerControlJob> claim(String type, String leaseOwner, Duration leaseDuration);

    Optional<WorkerControlJob> claimForRuntime(String type, String leaseOwner, String runtimeOwner,
            Duration leaseDuration, boolean allowExpiredRuntime);

    void complete(long jobId, String leaseOwner);

    boolean retryOrFail(long jobId, String leaseOwner, String error);
}
