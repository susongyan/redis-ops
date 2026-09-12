package io.github.redisops.worker.runtime;

import io.github.redisops.worker.domain.WorkerSyncTask;

public interface SyncTaskRunnerFactory {

    SyncTaskRunner create(WorkerSyncTask task, boolean recovery);
}
