package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.worker.domain.WorkerSyncFullProgress;
import io.github.redisops.sync.worker.domain.WorkerRuntimeObservation;
import io.github.redisops.sync.worker.domain.WorkerSyncChannelCheckpoint;
import io.github.redisops.sync.worker.domain.WorkerSyncMetricSnapshot;
import io.github.redisops.sync.worker.domain.WorkerSyncPrecheckReport;

/** Persistence operations used by the Worker data-plane execution path. */
public interface WorkerSyncExecutionPort {
    void appendTaskEvent(long taskId, String operator, String message);

    WorkerSyncPrecheckReport savePrecheck(WorkerSyncPrecheckReport report);

    void upsertFullProgress(WorkerSyncFullProgress progress);

    void upsertChannel(WorkerSyncChannelCheckpoint checkpoint);

    void saveMetric(WorkerSyncMetricSnapshot metric);

    void updateRuntimeObservation(WorkerRuntimeObservation observation);
}
