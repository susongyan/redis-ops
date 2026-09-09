package io.github.redisops.sync.worker.persistence;

import io.github.redisops.domain.sync.SyncFullProgress;
import io.github.redisops.domain.sync.SyncChannelCheckpoint;
import io.github.redisops.domain.sync.SyncMetricSnapshot;
import io.github.redisops.domain.sync.SyncPrecheckReport;
import io.github.redisops.domain.sync.SyncRepository;
import io.github.redisops.sync.worker.domain.WorkerSyncFullProgress;
import io.github.redisops.sync.worker.domain.WorkerRuntimeObservation;
import io.github.redisops.sync.worker.domain.WorkerSyncChannelCheckpoint;
import io.github.redisops.sync.worker.domain.WorkerSyncMetricSnapshot;
import io.github.redisops.sync.worker.domain.WorkerSyncPrecheckReport;
import org.springframework.stereotype.Component;

/** Temporary Platform-backed adapter; replace with Worker-owned MyBatis persistence during physical split. */
@Component
public class PlatformWorkerSyncExecutionAdapter implements WorkerSyncExecutionPort {
    private final SyncRepository repository;

    public PlatformWorkerSyncExecutionAdapter(SyncRepository repository) {
        this.repository = repository;
    }

    @Override
    public void appendTaskEvent(long taskId, String operator, String message) {
        repository.appendTaskEvent(taskId, operator, message);
    }

    @Override
    public WorkerSyncPrecheckReport savePrecheck(WorkerSyncPrecheckReport report) {
        SyncPrecheckReport saved = repository
                .savePrecheck(new SyncPrecheckReport(null, report.taskId(), report.status(),
                        report.reportJson(), report.checkedAt(), report.validUntil()));
        return new WorkerSyncPrecheckReport(saved.taskId(), saved.status(), saved.reportJson(), saved.checkedAt(),
                saved.validUntil());
    }

    @Override
    public void upsertFullProgress(WorkerSyncFullProgress progress) {
        repository.upsertFullProgress(new SyncFullProgress(null, progress.taskId(), progress.fullSyncEpoch(),
                progress.channelId(), progress.lane(), progress.stage(), progress.totalBytes(),
                progress.receivedBytes(),
                progress.parsedBytes(), progress.totalKeys(), progress.parsedKeys(), progress.appliedKeys(),
                progress.appliedBytes(), progress.status(), progress.startedAt(), progress.updatedAt()));
    }

    @Override
    public void upsertChannel(WorkerSyncChannelCheckpoint checkpoint) {
        repository.upsertChannel(new SyncChannelCheckpoint(null, checkpoint.taskId(), checkpoint.channelId(),
                checkpoint.sourceNode(), checkpoint.slotRangesJson(), checkpoint.replicationId(),
                checkpoint.receivedOffset(), checkpoint.appliedOffset(), checkpoint.status(), checkpoint.startedAt(),
                checkpoint.updatedAt()));
    }

    @Override
    public void saveMetric(WorkerSyncMetricSnapshot metric) {
        repository.saveMetric(new SyncMetricSnapshot(null, metric.taskId(), metric.channelId(), metric.rpoSeconds(),
                metric.estimatedLagSeconds(), metric.replicationOffsetGap(), metric.spoolBytes(),
                metric.sourceBytesPerSecond(), metric.targetBytesPerSecond(), metric.catchUpEtaSeconds(),
                metric.rpoMethod(), metric.confidence(), metric.capturedAt()));
    }

    @Override
    public void updateRuntimeObservation(WorkerRuntimeObservation observation) {
        repository.updateRuntimeObservation(observation.taskId(), observation.owner(), observation.phase(),
                observation.targetFenceGeneration(), observation.fencePublishedAt(), observation.recoveryAction(),
                observation.error());
    }
}
