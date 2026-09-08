package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncTaskStatus;
import io.github.redisops.domain.sync.SyncRuntime;
import java.util.Optional;

/** Worker-owned boundary for reading and reporting sync execution state. */
public interface SyncEngineStatePort {
    SyncTask get(long taskId);

    Optional<SyncRuntime> runtime(long taskId);

    void engineTransition(long taskId, long version, SyncTaskStatus status, Long rpoSeconds, String blockedReason,
            String error, String message, String operator);

    void appendEngineEvent(long taskId, String message, String operator);
}
