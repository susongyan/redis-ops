package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class NativeSyncRecoveryWorkerTest {
    @Test
    void submitsEveryExpiredRecoverableTaskToCoordinator() {
        SyncRepository sync = mock(SyncRepository.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        SyncTask first = task(1);
        SyncTask second = task(2);
        when(sync.findExpiredRecoverableTasks(10)).thenReturn(List.of(first, second));

        new NativeSyncRecoveryWorker(sync, coordinator).recoverExpiredRuntimes();

        verify(coordinator).recover(first);
        verify(coordinator).recover(second);
    }

    @Test
    void continuesWhenAnotherWorkerWinsOneClaim() {
        SyncRepository sync = mock(SyncRepository.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        SyncTask first = task(1);
        SyncTask second = task(2);
        when(sync.findExpiredRecoverableTasks(10)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("leased")).when(coordinator).recover(first);

        new NativeSyncRecoveryWorker(sync, coordinator).recoverExpiredRuntimes();

        verify(coordinator).recover(second);
    }

    private static SyncTask task(long id) {
        Instant now = Instant.now();
        return new SyncTask(id, "SYNC-" + id, null, 1, 2, SyncPurpose.ADHOC,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.INCR_SYNCING, "NATIVE_JAVA",
                0, 0, "[\"*\"]", "[]", 50_000, 100_000_000, 1024 * 1024,
                4, 100, null, true, "fenced", null, "epoch", 0L, null, 1, now, now, null);
    }
}
