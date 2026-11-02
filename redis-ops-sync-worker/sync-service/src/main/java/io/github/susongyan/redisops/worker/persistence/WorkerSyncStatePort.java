package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncRuntime;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;

import java.util.Optional;
import java.util.List;

/** Worker control-plane boundary; its implementation will move with Worker persistence. */
public interface WorkerSyncStatePort {
    WorkerSyncTask get(long taskId);

    /** Tasks present in the control table for the given ids; absent ids are simply not returned. */
    java.util.List<WorkerSyncTask> findTasks(java.util.Collection<Long> taskIds);

    Optional<WorkerSyncRuntime> runtime(long taskId);

    boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds);

    boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes);

    void releaseRuntime(long taskId, String owner, String phase, String error);

    /**
     * Releases the runner without clearing the runtime lease: the lease expires on its own and
     * the task stays visible to {@link #findExpiredRecoverableTasks} for re-adoption (graceful
     * shutdown path).
     */
    void parkRuntime(long taskId, String owner, String phase, String error);

    List<WorkerSyncTask> findExpiredRecoverableTasks(int limit);

    boolean canTransitionTo(long taskId, SyncContractStatus status);

    void engineTransition(long taskId, long version, SyncContractStatus status, Long rpoSeconds,
            String blockedReason, String error, String message, String operator);

    void appendEngineEvent(long taskId, String message, String operator);

    /** Task ids (from the given on-disk candidates) eligible for local spool deletion. */
    List<Long> findSpoolPurgeTaskIds(java.util.Collection<Long> taskIds, long graceSeconds);

    void zeroRuntimeSpoolBytes(long taskId);
}
