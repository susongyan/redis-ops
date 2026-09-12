package io.github.redisops.worker.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncControlWorkerTest {
    @Test
    void routesRunningControlsToRuntimeOwnerAndAllowsOnlyRecoveryActionsToTakeOver() {
        WorkerControlJobPort jobs = mock(WorkerControlJobPort.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        when(coordinator.instanceId()).thenReturn("worker-1");
        when(jobs.claim(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(jobs.claimForRuntime(anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        new SyncControlWorker(jobs, coordinator).poll();

        verify(jobs).claim(eq("SYNC_PRECHECK"), anyString(), eq(Duration.ofSeconds(30)));
        verify(jobs).claim(eq("SYNC_START"), anyString(), eq(Duration.ofSeconds(30)));
        verify(jobs).claimForRuntime(eq("SYNC_RESUME"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(true));
        verify(jobs).claimForRuntime(eq("SYNC_CANCEL"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(true));
        verify(jobs).claimForRuntime(eq("SYNC_PAUSE"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
        verify(jobs).claimForRuntime(eq("SYNC_FINISH"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
        verify(jobs).claimForRuntime(eq("SYNC_RATE_LIMIT"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
    }
}
