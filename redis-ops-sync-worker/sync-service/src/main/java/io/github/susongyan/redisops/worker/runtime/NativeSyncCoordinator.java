package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.domain.WorkerSyncPrecheckReport;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncStatePort;
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
            resetTarget(task);
            // Publish before starting channels: a runner may report its phase synchronously.
            service.engineTransition(taskId, task.version(), SyncContractStatus.FULL_SYNCING, null, null, null,
                    "target reset completed; starting replication runner", "sync:" + instanceId);
            runners.start(taskId);
        } catch (RuntimeException error) {
            runners.abort(taskId, error);
            failIfPossible(taskId, error, "sync runner failed");
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
            failIfPossible(taskId, error, "sync runner failed");
            throw error;
        }
    }
    public void restartFull(long taskId) {
        WorkerSyncTask task = service.get(taskId);
        if (task.status() != SyncContractStatus.RESUMING)
            throw new IllegalStateException("sync task must be RESUMING to restart full sync");
        try {
            // A zombie runner from the blocked attempt may still hold the runtime lease (BLOCKED tasks
            // renew indefinitely) and prepare() silently no-ops on a same-owner runner — abort it first.
            if (runners.isManaged(taskId))
                runners.abort(taskId, new IllegalStateException("full resync restart requested"));
            // recovery=false: fresh prepare clears this task's fence/checkpoint keys and claims the
            // runtime with an incremented fencing generation, fencing out any old writer.
            runners.prepare(task, instanceId, leaseSeconds, false);
            resetTarget(task);
            // Publish before starting channels: a runner may report its phase synchronously.
            service.engineTransition(taskId, task.version(), SyncContractStatus.FULL_SYNCING, null, null, null,
                    "full resync restart; fresh fullSyncEpoch published, starting replication runner",
                    "sync:" + instanceId);
            runners.start(taskId);
        } catch (RuntimeException error) {
            runners.abort(taskId, error);
            failIfPossible(taskId, error, "sync runner failed");
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
        WorkerSyncTask task = service.get(taskId);
        // Late or duplicate delivery after an earlier attempt already closed the task: ack quietly
        // instead of throwing "no active runner" on every retry.
        if (task.status() == SyncContractStatus.FINISHED || task.status() == SyncContractStatus.FAILED
                || task.status() == SyncContractStatus.CANCELLED)
            return;
        try {
            runners.finish(taskId);
            transition(taskId, SyncContractStatus.FINISHED, "final source offset applied");
        } catch (RuntimeException error) {
            // A channel that cannot drain must fail the task closed. runners.finish already removed
            // the runner and released its lease, so without this the task would sit in STOPPING
            // forever with an unclaimable retry job (test-env task 29).
            runners.abort(taskId, error);
            failIfPossible(taskId, error, "final offset drain failed");
            throw error;
        }
    }
    public void cancel(long taskId) {
        runners.cancel(taskId);
    }
    public void limits(long taskId) {
        runners.updateLimits(service.get(taskId));
    }
    private void resetTarget(WorkerSyncTask task) {
        long taskId = task.id();
        if (task.flushTarget()) {
            List<TargetResetter.ResetResult> resetResults = resetter.flush(task.targetClusterId(),
                    task.targetDb());
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
        } else {
            service.appendEngineEvent(taskId,
                    "TARGET_RESET_SKIPPED mode=PRESERVE; pre-existing target keys are kept and keys deleted on "
                            + "the source before this task started will remain on the target",
                    "sync:" + instanceId);
        }
    }
    private void transition(long taskId, SyncContractStatus status, String message) {
        WorkerSyncTask task = service.get(taskId);
        service.engineTransition(taskId, task.version(), status, task.lastRpoSeconds(), null, null, message,
                "sync:" + instanceId);
    }
    private void failIfPossible(long taskId, RuntimeException error, String message) {
        WorkerSyncTask current = service.get(taskId);
        if (current.status() != SyncContractStatus.FAILED
                && service.canTransitionTo(taskId, SyncContractStatus.FAILED))
            service.engineTransition(taskId, current.version(), SyncContractStatus.FAILED, null, null, safe(error),
                    message, "sync:" + instanceId);
    }
    private static String safe(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000));
    }

    String instanceId() {
        return instanceId;
    }
}
