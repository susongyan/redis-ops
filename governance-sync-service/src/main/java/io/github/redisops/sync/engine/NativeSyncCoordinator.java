package io.github.redisops.sync.engine;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;

@Component
public class NativeSyncCoordinator {
    private final SyncService service;
    private final SyncPrecheckExecutor prechecks;
    private final TargetResetter resetter;
    private final NativeSyncRunnerManager runners;
    private final String instanceId;
    private final long leaseSeconds;
    public NativeSyncCoordinator(SyncService service, SyncPrecheckExecutor prechecks, TargetResetter resetter,
            NativeSyncRunnerManager runners,
            @Value("${sync.engine.instance-id:local-sync}") String instanceId,
            @Value("${sync.engine.lease-seconds:30}") long leaseSeconds) {
        this.service = service;
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
        if (task.status() != SyncTaskStatus.STARTING)
            throw new IllegalStateException("sync task must be STARTING");
        try {
            var previousRuntime = service.runtime(taskId);
            if (previousRuntime.isPresent() && previousRuntime.get().fencingGeneration() > 0) {
                recover(task);
                return;
            }
            runners.prepare(task, instanceId, leaseSeconds, false);
            List<TargetResetter.ResetResult> resetResults = resetter.flush(task.targetClusterId(), task.targetDb());
            if (resetResults == null)
                resetResults = List.of();
            for (TargetResetter.ResetResult result : resetResults)
                service.appendEngineEvent(taskId,
                        "TARGET_FLUSH endpoint=" + result.endpoint() + " db=" + result.database()
                                + " result=" + (result.success() ? "SUCCESS" : "FAILED")
                                + (result.error() == null ? "" : " error=" + result.error()),
                        "sync:" + instanceId);
            if (resetResults.stream().anyMatch(result -> !result.success()))
                throw new IllegalStateException("one or more target Redis nodes failed to flush");
            runners.start(taskId);
            service.engineTransition(taskId, task.version(), SyncTaskStatus.FULL_SYNCING, null, null, null,
                    "target reset completed; replication runner started", "sync:" + instanceId);
        } catch (RuntimeException error) {
            runners.abort(taskId, error);
            failIfPossible(taskId, error);
            throw error;
        }
    }
    public void pause(long taskId) {
        runners.pause(taskId);
        transition(taskId, SyncTaskStatus.PAUSED, "target apply paused; source spool remains active");
    }
    public void resume(long taskId) {
        SyncTask task = service.get(taskId);
        runners.resume(task, instanceId, leaseSeconds);
        transition(taskId, task.fullSyncEpoch() == null ? SyncTaskStatus.FULL_SYNCING : SyncTaskStatus.INCR_SYNCING,
                "sync resumed");
    }
    public void recover(SyncTask task) {
        if (runners.isManaged(task.id()))
            return;
        try {
            runners.prepareRecovery(task, instanceId, leaseSeconds);
            service.appendEngineEvent(task.id(), "LEASE_EXPIRED; automatic takeover claimed",
                    "sync:" + instanceId);
            SyncTask current = service.get(task.id());
            if (current.status() != SyncTaskStatus.RESUMING
                    && current.status().canTransitionTo(SyncTaskStatus.RESUMING))
                service.engineTransition(task.id(), current.version(), SyncTaskStatus.RESUMING,
                        current.lastRpoSeconds(), null, null,
                        "TAKEOVER_STARTED; target fence published", "sync:" + instanceId);
            runners.resumePrepared(task.id());
        } catch (RuntimeException error) {
            runners.abort(task.id(), error);
            throw error;
        }
    }
    public void finish(long taskId) {
        runners.finish(taskId);
        transition(taskId, SyncTaskStatus.FINISHED, "final source offset applied");
    }
    public void cancel(long taskId) {
        runners.cancel(taskId);
    }
    public void limits(long taskId) {
        runners.updateLimits(service.get(taskId));
    }
    private void transition(long taskId, SyncTaskStatus status, String message) {
        SyncTask task = service.get(taskId);
        service.engineTransition(taskId, task.version(), status, task.lastRpoSeconds(), null, null, message,
                "sync:" + instanceId);
    }
    private void failIfPossible(long taskId, RuntimeException error) {
        SyncTask current = service.get(taskId);
        if (current.status().canTransitionTo(SyncTaskStatus.FAILED))
            service.engineTransition(taskId, current.version(), SyncTaskStatus.FAILED, null, null, safe(error),
                    "sync runner failed to start", "sync:" + instanceId);
    }
    private static String safe(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000));
    }

    String instanceId() {
        return instanceId;
    }
}
