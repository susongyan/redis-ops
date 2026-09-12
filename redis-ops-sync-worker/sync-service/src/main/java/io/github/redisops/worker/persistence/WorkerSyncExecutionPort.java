package io.github.redisops.worker.persistence;

import io.github.redisops.worker.domain.WorkerSyncFullProgress;
import io.github.redisops.worker.domain.WorkerRuntimeObservation;
import io.github.redisops.worker.domain.WorkerSyncChannelCheckpoint;
import io.github.redisops.worker.domain.WorkerSyncMetricSnapshot;
import io.github.redisops.worker.domain.WorkerSyncPrecheckReport;

/** Persistence operations used by the Worker data-plane execution path. */
public interface WorkerSyncExecutionPort {
    void appendTaskEvent(long taskId, String operator, String message);

    WorkerSyncPrecheckReport savePrecheck(WorkerSyncPrecheckReport report);

    void upsertFullProgress(WorkerSyncFullProgress progress);

    void upsertChannel(WorkerSyncChannelCheckpoint checkpoint);

    void saveMetric(WorkerSyncMetricSnapshot metric);

    void updateRuntimeObservation(WorkerRuntimeObservation observation);
}
