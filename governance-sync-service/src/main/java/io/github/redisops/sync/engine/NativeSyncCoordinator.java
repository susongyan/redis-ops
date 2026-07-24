package io.github.redisops.sync.engine;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NativeSyncCoordinator {
    private final SyncService service;
    private final SyncRepository sync;
    private final SyncPrecheckExecutor prechecks;
    private final TargetResetter resetter;
    private final NativeSyncRunnerManager runners;
    private final String instanceId;
    private final long leaseSeconds;
    public NativeSyncCoordinator(SyncService service, SyncRepository sync, SyncPrecheckExecutor prechecks,
            TargetResetter resetter, NativeSyncRunnerManager runners,
            @Value("${sync.engine.instance-id:local-sync}") String instanceId,
            @Value("${sync.engine.lease-seconds:30}") long leaseSeconds) {
        this.service = service;
        this.sync = sync;
        this.prechecks = prechecks;
        this.resetter = resetter;
        this.runners = runners;
        this.instanceId = instanceId + "-" + UUID.randomUUID();
        this.leaseSeconds = leaseSeconds;
    }
    public void precheck(long taskId) {
        SyncTask task = service.get(taskId);
        SyncPrecheckReport report = prechecks.execute(task);
        service.engineTransition(taskId, task.version(),
                report.validAt(java.time.Instant.now()) ? SyncTaskStatus.READY : SyncTaskStatus.FAILED, null, null,
                report.validAt(java.time.Instant.now()) ? null : "precheck failed",
                "precheck " + report.status(), "sync:" + instanceId);
    }
    public void start(long taskId) {
        SyncTask task = service.get(taskId);
        if (!sync.claimRuntime(taskId, UUID.randomUUID().toString(), instanceId, leaseSeconds))
            throw new IllegalStateException("sync runtime is already leased");
        try {
            resetter.flush(task.targetClusterId(), task.targetDb());
            task = service.engineTransition(taskId, task.version(), SyncTaskStatus.FULL_SYNCING, null, null, null,
                    "target reset completed; full sync started", "sync:" + instanceId);
            runners.start(task, instanceId, leaseSeconds);
        } catch (RuntimeException error) {
            sync.releaseRuntime(taskId, instanceId, "FAILED", safe(error));
            throw error;
        }
    }
    public void pause(long taskId) {
        runners.pause(taskId);
        transition(taskId, SyncTaskStatus.PAUSED, "target apply paused; source spool remains active");
    }
    public void resume(long taskId) {
        SyncTask task = service.get(taskId);
        runners.start(task, instanceId, leaseSeconds);
        transition(taskId, task.fullSyncEpoch() == null ? SyncTaskStatus.FULL_SYNCING : SyncTaskStatus.INCR_SYNCING,
                "sync resumed");
    }
    public void finish(long taskId) {
        runners.finish(taskId);
        transition(taskId, SyncTaskStatus.FINISHED, "final source offset applied");
    }
    public void cancel(long taskId) {
        runners.cancel(taskId);
        sync.releaseRuntime(taskId, instanceId, "CANCELLED", null);
    }
    public void limits(long taskId) {
        runners.updateLimits(service.get(taskId));
    }
    private void transition(long taskId, SyncTaskStatus status, String message) {
        SyncTask task = service.get(taskId);
        service.engineTransition(taskId, task.version(), status, task.lastRpoSeconds(), null, null, message,
                "sync:" + instanceId);
    }
    private static String safe(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000));
    }
}
