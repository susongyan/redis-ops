package io.github.redisops.worker.runtime;

import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.worker.domain.WorkerSyncTask;
import io.github.redisops.worker.persistence.WorkerSyncStatePort;
import org.springframework.stereotype.Component;

@Component
public class SyncRunnerStateReporter {
    private final WorkerSyncStatePort service;

    public SyncRunnerStateReporter(WorkerSyncStatePort service) {
        this.service = service;
    }

    public void transition(long taskId, SyncContractStatus target, Long rpo, String blockedReason, String error,
            String message) {
        WorkerSyncTask task = service.get(taskId);
        if (task.status() == target || !service.canTransitionTo(taskId, target))
            return;
        service.engineTransition(taskId, task.version(), target, rpo, blockedReason, safe(error), message,
                "sync:runner");
    }

    private static String safe(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 1000));
    }
}
