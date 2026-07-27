package io.github.redisops.sync.engine;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncTaskStatus;
import org.springframework.stereotype.Component;

@Component
public class SyncRunnerStateReporter {
    private final SyncService service;

    public SyncRunnerStateReporter(SyncService service) {
        this.service = service;
    }

    public void transition(long taskId, SyncTaskStatus target, Long rpo, String blockedReason, String error,
            String message) {
        SyncTask task = service.get(taskId);
        if (task.status() == target || !task.status().canTransitionTo(target))
            return;
        service.engineTransition(taskId, task.version(), target, rpo, blockedReason, safe(error), message,
                "sync:runner");
    }

    private static String safe(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 1000));
    }
}
