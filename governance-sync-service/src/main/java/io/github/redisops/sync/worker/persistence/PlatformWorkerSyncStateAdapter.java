package io.github.redisops.sync.worker.persistence;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.SyncRepository;
import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.sync.worker.domain.WorkerSyncRuntime;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

/** Temporary compatibility adapter; it will be replaced by Worker-owned MyBatis persistence. */
@Component
public final class PlatformWorkerSyncStateAdapter implements WorkerSyncStatePort {
    private final SyncService delegate;
    private final SyncRepository repository;

    public PlatformWorkerSyncStateAdapter(SyncService delegate, SyncRepository repository) {
        this.delegate = delegate;
        this.repository = repository;
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
    public boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds) {
        return repository.claimRuntime(taskId, runtimeId, owner, leaseSeconds);
    }

    @Override
    public boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes) {
        return repository.renewRuntime(taskId, owner, leaseSeconds, phase, spoolBytes);
    }

    @Override
    public void releaseRuntime(long taskId, String owner, String phase, String error) {
        repository.releaseRuntime(taskId, owner, phase, error);
    }

    @Override
    public List<WorkerSyncTask> findExpiredRecoverableTasks(int limit) {
        return repository.findExpiredRecoverableTasks(limit).stream()
                .map(PlatformWorkerSyncStateMapper::toWorkerTask)
                .toList();
    }

    @Override
    public boolean canTransitionTo(long taskId, SyncContractStatus status) {
        return delegate.get(taskId).status()
                .canTransitionTo(PlatformWorkerSyncStateMapper.toPlatformStatus(status));
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
