package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncTask;

public interface SyncTaskRunnerFactory {

    SyncTaskRunner create(SyncTask task, boolean recovery);
}
