package io.github.redisops.worker.persistence;

import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.worker.domain.WorkerSyncRuntime;
import io.github.redisops.worker.domain.WorkerSyncTask;

import java.util.Optional;
import java.util.List;

/** Worker control-plane boundary; its implementation will move with Worker persistence. */
public interface WorkerSyncStatePort {
    WorkerSyncTask get(long taskId);

    Optional<WorkerSyncRuntime> runtime(long taskId);

    boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds);

    boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes);

    void releaseRuntime(long taskId, String owner, String phase, String error);

    List<WorkerSyncTask> findExpiredRecoverableTasks(int limit);

    boolean canTransitionTo(long taskId, SyncContractStatus status);

    void engineTransition(long taskId, long version, SyncContractStatus status, Long rpoSeconds,
            String blockedReason, String error, String message, String operator);

    void appendEngineEvent(long taskId, String message, String operator);
}
