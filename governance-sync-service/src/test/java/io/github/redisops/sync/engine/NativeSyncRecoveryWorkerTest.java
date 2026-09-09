package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.*;
import io.github.redisops.sync.contract.SyncContractStatus;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;
import io.github.redisops.sync.worker.persistence.WorkerSyncStatePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class NativeSyncRecoveryWorkerTest {
    @Test
    void submitsEveryExpiredRecoverableTaskToCoordinator() {
        WorkerSyncStatePort sync = mock(WorkerSyncStatePort.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        WorkerSyncTask first = task(1);
        WorkerSyncTask second = task(2);
        when(sync.findExpiredRecoverableTasks(10)).thenReturn(List.of(first, second));

        new NativeSyncRecoveryWorker(sync, coordinator).recoverExpiredRuntimes();

        verify(coordinator).recover(first);
        verify(coordinator).recover(second);
    }

    @Test
    void continuesWhenAnotherWorkerWinsOneClaim() {
        WorkerSyncStatePort sync = mock(WorkerSyncStatePort.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        WorkerSyncTask first = task(1);
        WorkerSyncTask second = task(2);
        when(sync.findExpiredRecoverableTasks(10)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("leased")).when(coordinator).recover(first);

        new NativeSyncRecoveryWorker(sync, coordinator).recoverExpiredRuntimes();

        verify(coordinator).recover(second);
    }

    private static WorkerSyncTask task(long id) {
        Instant now = Instant.now();
        return new WorkerSyncTask(id, "SYNC-" + id, null, 1, 2, "ADHOC", "FULL_AND_INCREMENTAL",
                SyncContractStatus.INCR_SYNCING, "NATIVE_JAVA",
                0, 0, "[\"*\"]", "[]", "{}", 50_000, 100_000_000, 1024 * 1024,
                4, 100, null, true, "fenced", null, "epoch", 0L, null, 1, now, now, null);
    }
}
