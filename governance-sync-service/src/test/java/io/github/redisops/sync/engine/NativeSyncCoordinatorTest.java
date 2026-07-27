package io.github.redisops.sync.engine;

import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeSyncCoordinatorTest {
    @Test
    void preparesBeforeTargetResetAndStartsAfterReset() {
        SyncService service = mock(SyncService.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        SyncTask task = task();
        when(service.get(1)).thenReturn(task);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.start(1);

        InOrder order = inOrder(runners, resetter, service);
        order.verify(runners).prepare(eq(task), anyString(), eq(30L), eq(false));
        order.verify(resetter).flush(22, 0);
        order.verify(runners).start(1);
        order.verify(service).engineTransition(eq(1L), eq(0L), eq(SyncTaskStatus.FULL_SYNCING), isNull(),
                isNull(), isNull(), anyString(), anyString());
    }

    @Test
    void prepareFailureNeverResetsTarget() {
        SyncService service = mock(SyncService.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        SyncTask task = task();
        when(service.get(1)).thenReturn(task);
        doThrow(new IllegalStateException("not ready")).when(runners)
                .prepare(eq(task), anyString(), eq(30L), eq(false));
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.start(1));

        verifyNoInteractions(resetter);
        verify(runners).abort(eq(1L), any());
        verify(service).engineTransition(eq(1L), eq(0L), eq(SyncTaskStatus.FAILED), isNull(), isNull(),
                eq("not ready"), anyString(), anyString());
    }

    @Test
    void recordsEveryTargetResetFailureAndNeverStartsRunner() {
        SyncService service = mock(SyncService.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        SyncTask task = task();
        when(service.get(1)).thenReturn(task);
        when(resetter.flush(22, 0)).thenReturn(List.of(
                new TargetResetter.ResetResult("target-a:6379", 0, true, null, Instant.now()),
                new TargetResetter.ResetResult("target-b:6379", 0, false, "timeout", Instant.now())));
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.start(1));

        verify(service, times(2)).appendEngineEvent(eq(1L), contains("TARGET_FLUSH"), anyString());
        verify(runners, never()).start(anyLong());
    }

    @Test
    void retriesCrashedStartAsRecoveryWithoutFlushingTargetAgain() {
        SyncService service = mock(SyncService.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        SyncTask task = task();
        Instant now = Instant.now();
        when(service.get(1)).thenReturn(task);
        when(service.runtime(1)).thenReturn(Optional.of(new SyncRuntime(1, "old-runtime", "old-worker",
                now.minusSeconds(1), 1, "FULL_SYNCING", now.minusSeconds(10), 0, 1L, now.minusSeconds(10),
                0, null, null, now.minusSeconds(20), now)));
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.start(1);

        verify(runners).prepareRecovery(eq(task), anyString(), eq(30L));
        verifyNoInteractions(resetter);
    }

    private static SyncTask task() {
        Instant now = Instant.now();
        return new SyncTask(1L, "SYNC-T", null, 11, 22, SyncPurpose.MIGRATION,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", 50_000, 100_000_000, 1024 * 1024, 4, 100, "START", true, "ticket",
                null, "epoch", null, null, 0, now, now, null);
    }
}
