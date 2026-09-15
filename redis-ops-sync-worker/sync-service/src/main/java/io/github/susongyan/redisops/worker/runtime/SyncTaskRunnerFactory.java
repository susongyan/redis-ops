package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;

public interface SyncTaskRunnerFactory {

    SyncTaskRunner create(WorkerSyncTask task, boolean recovery);
}
