package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncRuntime;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncStatePort;
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
    void resumeFailureAbortsAndFailsClosed() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        when(service.get(1)).thenReturn(task(SyncContractStatus.RESUMING, 0),
                task(SyncContractStatus.RESUMING, 0), task(SyncContractStatus.INCR_SYNCING, 1));
        when(service.canTransitionTo(1, SyncContractStatus.FAILED)).thenReturn(true);
        doThrow(new IllegalStateException("prepare failed")).when(runners).resume(any(), anyString(), anyLong());
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class),
                mock(TargetResetter.class), runners, "test", 30);
        assertThrows(IllegalStateException.class, () -> coordinator.resume(1));
        verify(runners).abort(eq(1L), any());
        verify(service).engineTransition(eq(1L), eq(1L), eq(SyncContractStatus.FAILED), isNull(), isNull(),
                eq("prepare failed"), anyString(), anyString());
    }

    @Test
    void finishDrainFailureFailsTheTaskClosed() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        when(service.get(1)).thenReturn(task(SyncContractStatus.STOPPING, 6),
                task(SyncContractStatus.STOPPING, 6));
        when(service.canTransitionTo(1, SyncContractStatus.FAILED)).thenReturn(true);
        doThrow(new IllegalStateException("cannot finish Cluster channel master-1-6379"))
                .when(runners).finish(1);
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class),
                mock(TargetResetter.class), runners, "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.finish(1));

        // The runner was already removed by runners.finish, so abort is the safety net; the task
        // must leave STOPPING or no worker can ever drive it again (unclaimable retry job).
        verify(runners).abort(eq(1L), any());
        verify(service).engineTransition(eq(1L), eq(6L), eq(SyncContractStatus.FAILED), isNull(), isNull(),
                contains("cannot finish Cluster channel"), contains("drain failed"), anyString());
    }

    @Test
    void finishAcksQuietlyWhenTaskAlreadyClosed() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        for (SyncContractStatus status : new SyncContractStatus[]{SyncContractStatus.FINISHED,
                SyncContractStatus.FAILED, SyncContractStatus.CANCELLED}) {
            when(service.get(1)).thenReturn(task(status, 6));
            var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class),
                    mock(TargetResetter.class), runners, "test", 30);
            coordinator.finish(1);
        }
        verify(runners, never()).finish(anyLong());
        verify(service, never()).engineTransition(anyLong(), anyLong(), any(), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    void resumeStateWriteFailureNeverResumesRunner() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        when(service.get(1)).thenReturn(task(SyncContractStatus.RESUMING, 0));
        doThrow(new IllegalStateException("version conflict")).when(service).engineTransition(anyLong(), anyLong(),
                eq(SyncContractStatus.INCR_SYNCING), any(), any(), any(), anyString(), anyString());
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class),
                mock(TargetResetter.class), runners, "test", 30);
        assertThrows(IllegalStateException.class, () -> coordinator.resume(1));
        verify(runners, never()).resume(any(), anyString(), anyLong());
        verify(runners).abort(eq(1L), any());
    }

    @Test
    void publishesResumeStateBeforeChannelsCanReportCaughtUp() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.RESUMING, 0);
        when(service.get(1)).thenReturn(task);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.resume(1);

        InOrder order = inOrder(service, runners);
        order.verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.INCR_SYNCING), isNull(),
                isNull(), isNull(), anyString(), anyString());
        order.verify(runners).resume(eq(task), eq(coordinator.instanceId()), eq(30L));
    }

    @Test
    void publishesFullSyncStateAfterResetBeforeRunnerCanReportItsPhase() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task();
        when(service.get(1)).thenReturn(task);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.start(1);

        InOrder order = inOrder(runners, resetter, service);
        order.verify(runners).prepare(eq(task), anyString(), eq(30L), eq(false));
        order.verify(resetter).flush(22, 0);
        order.verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FULL_SYNCING), isNull(),
                isNull(), isNull(), anyString(), anyString());
        order.verify(runners).start(1);
    }

    @Test
    void prepareFailureNeverResetsTarget() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task();
        when(service.get(1)).thenReturn(task);
        when(service.canTransitionTo(1, SyncContractStatus.FAILED)).thenReturn(true);
        doThrow(new IllegalStateException("not ready")).when(runners)
                .prepare(eq(task), anyString(), eq(30L), eq(false));
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.start(1));

        verifyNoInteractions(resetter);
        verify(runners).abort(eq(1L), any());
        verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FAILED), isNull(), isNull(),
                eq("not ready"), anyString(), anyString());
    }

    @Test
    void recordsEveryTargetResetFailureAndNeverStartsRunner() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task();
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
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task();
        Instant now = Instant.now();
        when(service.get(1)).thenReturn(task);
        when(service.runtime(1)).thenReturn(Optional.of(new WorkerSyncRuntime(1, "old-runtime", "old-worker",
                now.minusSeconds(1), 1, "FULL_SYNCING", now.minusSeconds(10), 0, 1L, now.minusSeconds(10),
                0, null, null, now.minusSeconds(20), now)));
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.start(1);

        verify(runners).prepareRecovery(eq(task), anyString(), eq(30L));
        verifyNoInteractions(resetter);
    }

    @Test
    void preserveModeSkipsTargetFlushAndStillStartsRunner() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.STARTING, 0, "PRESERVE");
        when(service.get(1)).thenReturn(task);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.start(1);

        verifyNoInteractions(resetter);
        verify(service).appendEngineEvent(eq(1L), contains("TARGET_RESET_SKIPPED"), anyString());
        InOrder order = inOrder(runners, service);
        order.verify(runners).prepare(eq(task), anyString(), eq(30L), eq(false));
        order.verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FULL_SYNCING), isNull(),
                isNull(), isNull(), anyString(), anyString());
        order.verify(runners).start(1);
    }

    @Test
    void restartFullAbortsZombieRunnerThenPreparesFlushesAndStarts() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.RESUMING, 0);
        when(service.get(1)).thenReturn(task);
        when(runners.isManaged(1)).thenReturn(true);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.restartFull(1);

        InOrder order = inOrder(runners, resetter, service);
        order.verify(runners).abort(eq(1L), any());
        order.verify(runners).prepare(eq(task), anyString(), eq(30L), eq(false));
        order.verify(resetter).flush(22, 0);
        order.verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FULL_SYNCING), isNull(),
                isNull(), isNull(), anyString(), anyString());
        order.verify(runners).start(1);
    }

    @Test
    void restartFullInPreserveModeSkipsFlushWithoutAbortingUnmanagedRunner() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        SyncPrecheckExecutor prechecks = mock(SyncPrecheckExecutor.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.RESUMING, 0, "PRESERVE");
        when(service.get(1)).thenReturn(task);
        when(runners.isManaged(1)).thenReturn(false);
        var coordinator = new NativeSyncCoordinator(service, prechecks, resetter, runners, "test", 30);

        coordinator.restartFull(1);

        verify(runners, never()).abort(anyLong(), any());
        verifyNoInteractions(resetter);
        verify(service).appendEngineEvent(eq(1L), contains("TARGET_RESET_SKIPPED"), anyString());
        verify(runners).prepare(eq(task), anyString(), eq(30L), eq(false));
        verify(runners).start(1);
    }

    @Test
    void restartFullRejectsTasksThatAreNotResuming() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        when(service.get(1)).thenReturn(task(SyncContractStatus.BLOCKED, 0));
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class),
                mock(TargetResetter.class), runners, "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.restartFull(1));

        verify(runners, never()).prepare(any(), anyString(), anyLong(), anyBoolean());
        verify(runners, never()).start(anyLong());
    }

    @Test
    void restartFullFlushFailureFailsClosedAndNeverStartsRunner() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.RESUMING, 0);
        when(service.get(1)).thenReturn(task);
        when(service.canTransitionTo(1, SyncContractStatus.FAILED)).thenReturn(true);
        when(resetter.flush(22, 0)).thenReturn(List.of(
                new TargetResetter.ResetResult("target-a:6379", 0, false, "timeout", Instant.now())));
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class), resetter, runners,
                "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.restartFull(1));

        verify(service).appendEngineEvent(eq(1L), contains("TARGET_FLUSH"), anyString());
        verify(runners, never()).start(anyLong());
        verify(runners).abort(eq(1L), any());
        verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FAILED), isNull(), isNull(),
                contains("failed to flush"), anyString(), anyString());
    }

    @Test
    void restartFullPrepareFailureNeverResetsTarget() {
        WorkerSyncStatePort service = mock(WorkerSyncStatePort.class);
        TargetResetter resetter = mock(TargetResetter.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        WorkerSyncTask task = task(SyncContractStatus.RESUMING, 0);
        when(service.get(1)).thenReturn(task);
        when(service.canTransitionTo(1, SyncContractStatus.FAILED)).thenReturn(true);
        doThrow(new IllegalStateException("not ready")).when(runners)
                .prepare(eq(task), anyString(), eq(30L), eq(false));
        var coordinator = new NativeSyncCoordinator(service, mock(SyncPrecheckExecutor.class), resetter, runners,
                "test", 30);

        assertThrows(IllegalStateException.class, () -> coordinator.restartFull(1));

        verifyNoInteractions(resetter);
        verify(runners, never()).start(anyLong());
        verify(service).engineTransition(eq(1L), eq(0L), eq(SyncContractStatus.FAILED), isNull(), isNull(),
                eq("not ready"), anyString(), anyString());
    }

    private static WorkerSyncTask task() {
        return task(SyncContractStatus.STARTING, 0);
    }

    private static WorkerSyncTask task(SyncContractStatus status, long version) {
        return task(status, version, "FLUSH");
    }

    private static WorkerSyncTask task(SyncContractStatus status, long version, String resetMode) {
        Instant now = Instant.now();
        return new WorkerSyncTask(1L, "SYNC-T", null, 11, 22, "MIGRATION", "FULL_AND_INCREMENTAL",
                status, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]", "{}", resetMode, 50_000,
                100_000_000, 1024 * 1024, 4, 100, "START", true, "ticket", null, "epoch", null, null, version,
                now, now, null);
    }
}
