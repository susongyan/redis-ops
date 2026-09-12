package io.github.redisops.worker.runtime;

import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.worker.domain.WorkerSyncTask;
import io.github.redisops.worker.domain.WorkerSyncPrecheckReport;
import io.github.redisops.worker.persistence.WorkerSyncStatePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;

@Component
public class NativeSyncCoordinator {
    private final WorkerSyncStatePort service;
    private final SyncPrecheckExecutor prechecks;
    private final TargetResetter resetter;
    private final NativeSyncRunnerManager runners;
    private final String instanceId;
    private final long leaseSeconds;
    public NativeSyncCoordinator(WorkerSyncStatePort service, SyncPrecheckExecutor prechecks, TargetResetter resetter,
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
        WorkerSyncTask task = service.get(taskId);
        WorkerSyncPrecheckReport report = prechecks.execute(task);
        service.engineTransition(taskId, task.version(),
                report.validAt(java.time.Instant.now()) ? SyncContractStatus.READY : SyncContractStatus.FAILED, null,
                null,
                report.validAt(java.time.Instant.now()) ? null : "precheck failed",
                "precheck " + report.status(), "sync:" + instanceId);
    }
    public void start(long taskId) {
        WorkerSyncTask task = service.get(taskId);
        if (task.status() != SyncContractStatus.STARTING)
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
            // Publish before starting channels: a runner may report its phase synchronously.
            service.engineTransition(taskId, task.version(), SyncContractStatus.FULL_SYNCING, null, null, null,
                    "target reset completed; starting replication runner", "sync:" + instanceId);
            runners.start(taskId);
        } catch (RuntimeException error) {
            runners.abort(taskId, error);
            failIfPossible(taskId, error);
            throw error;
        }
    }
    public void pause(long taskId) {
        runners.pause(taskId);
        transition(taskId, SyncContractStatus.PAUSED, "target apply paused; source spool remains active");
    }
    public void resume(long taskId) {
        WorkerSyncTask task = service.get(taskId);
        try {
            transition(taskId,
                    task.fullSyncEpoch() == null ? SyncContractStatus.FULL_SYNCING : SyncContractStatus.INCR_SYNCING,
                    "resuming sync runner");
            runners.resume(task, instanceId, leaseSeconds);
        } catch (RuntimeException error) {
            runners.abort(taskId, error);
            failIfPossible(taskId, error);
            throw error;
        }
    }
    public void recover(WorkerSyncTask task) {
        if (runners.isManaged(task.id()))
            return;
        try {
            runners.prepareRecovery(task, instanceId, leaseSeconds);
            service.appendEngineEvent(task.id(), "LEASE_EXPIRED; automatic takeover claimed",
                    "sync:" + instanceId);
            WorkerSyncTask current = service.get(task.id());
            if (current.status() != SyncContractStatus.RESUMING
                    && service.canTransitionTo(task.id(), SyncContractStatus.RESUMING))
                service.engineTransition(task.id(), current.version(), SyncContractStatus.RESUMING,
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
        transition(taskId, SyncContractStatus.FINISHED, "final source offset applied");
    }
    public void cancel(long taskId) {
        runners.cancel(taskId);
    }
    public void limits(long taskId) {
        runners.updateLimits(service.get(taskId));
    }
    private void transition(long taskId, SyncContractStatus status, String message) {
        WorkerSyncTask task = service.get(taskId);
        service.engineTransition(taskId, task.version(), status, task.lastRpoSeconds(), null, null, message,
                "sync:" + instanceId);
    }
    private void failIfPossible(long taskId, RuntimeException error) {
        WorkerSyncTask current = service.get(taskId);
        if (current.status() != SyncContractStatus.FAILED
                && service.canTransitionTo(taskId, SyncContractStatus.FAILED))
            service.engineTransition(taskId, current.version(), SyncContractStatus.FAILED, null, null, safe(error),
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
