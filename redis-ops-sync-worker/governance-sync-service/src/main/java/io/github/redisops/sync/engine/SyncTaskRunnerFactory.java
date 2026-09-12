package io.github.redisops.sync.engine;

import io.github.redisops.sync.worker.domain.WorkerSyncTask;

public interface SyncTaskRunnerFactory {

    SyncTaskRunner create(WorkerSyncTask task, boolean recovery);
}
