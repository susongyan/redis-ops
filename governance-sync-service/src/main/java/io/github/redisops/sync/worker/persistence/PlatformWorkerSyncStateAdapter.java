package io.github.redisops.sync.worker.persistence;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.sync.worker.domain.WorkerSyncRuntime;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Temporary compatibility adapter; it will be replaced by Worker-owned MyBatis persistence. */
@Component
public final class PlatformWorkerSyncStateAdapter implements WorkerSyncStatePort {
    private final SyncService delegate;

    public PlatformWorkerSyncStateAdapter(SyncService delegate) {
        this.delegate = delegate;
    }

    @Override
    public WorkerSyncTask get(long taskId) {
        return PlatformWorkerSyncStateMapper.toWorkerTask(delegate.get(taskId));
    }

    @Override
    public Optional<WorkerSyncRuntime> runtime(long taskId) {
        return delegate.runtime(taskId).map(PlatformWorkerSyncStateMapper::toWorkerRuntime);
    }

    @Override
    public void engineTransition(long taskId, long version, SyncContractStatus status, Long rpoSeconds,
            String blockedReason, String error, String message, String operator) {
        delegate.engineTransition(taskId, version, PlatformWorkerSyncStateMapper.toPlatformStatus(status), rpoSeconds,
                blockedReason, error, message, operator);
    }

    @Override
    public void appendEngineEvent(long taskId, String message, String operator) {
        delegate.appendEngineEvent(taskId, message, operator);
    }
}
