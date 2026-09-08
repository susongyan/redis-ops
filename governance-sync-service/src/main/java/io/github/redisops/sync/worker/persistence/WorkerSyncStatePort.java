package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.sync.worker.domain.WorkerSyncRuntime;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;

import java.util.Optional;

/** Worker control-plane boundary; its implementation will move with Worker persistence. */
public interface WorkerSyncStatePort {
    WorkerSyncTask get(long taskId);

    Optional<WorkerSyncRuntime> runtime(long taskId);

    void engineTransition(long taskId, long version, SyncContractStatus status, Long rpoSeconds,
            String blockedReason, String error, String message, String operator);

    void appendEngineEvent(long taskId, String message, String operator);
}
