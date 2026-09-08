package io.github.redisops.sync.engine;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncTaskStatus;
import io.github.redisops.domain.sync.SyncRuntime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Temporary compatibility adapter; it is the only Worker-side Platform Service dependency. */
@Component
public final class PlatformSyncEngineStateAdapter implements SyncEngineStatePort {
    private final SyncService delegate;

    public PlatformSyncEngineStateAdapter(SyncService delegate) {
        this.delegate = delegate;
    }

    @Override
    public SyncTask get(long taskId) {
        return delegate.get(taskId);
    }

    @Override
    public Optional<SyncRuntime> runtime(long taskId) {
        return delegate.runtime(taskId);
    }

    @Override
    public void engineTransition(long taskId, long version, SyncTaskStatus status, Long rpoSeconds, String blockedReason,
            String error, String message, String operator) {
        delegate.engineTransition(taskId, version, status, rpoSeconds, blockedReason, error, message, operator);
    }

    @Override
    public void appendEngineEvent(long taskId, String message, String operator) {
        delegate.appendEngineEvent(taskId, message, operator);
    }
}
