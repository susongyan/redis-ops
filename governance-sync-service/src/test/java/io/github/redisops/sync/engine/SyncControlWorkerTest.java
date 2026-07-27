package io.github.redisops.sync.engine;

import io.github.redisops.domain.job.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncControlWorkerTest {
    @Test
    void routesRunningControlsToRuntimeOwnerAndAllowsOnlyRecoveryActionsToTakeOver() {
        JobRepository jobs = mock(JobRepository.class);
        NativeSyncCoordinator coordinator = mock(NativeSyncCoordinator.class);
        when(coordinator.instanceId()).thenReturn("worker-1");
        when(jobs.claimNext(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(jobs.claimNextRouted(anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        new SyncControlWorker(jobs, coordinator).poll();

        verify(jobs).claimNext(eq("SYNC_PRECHECK"), anyString(), eq(Duration.ofSeconds(30)));
        verify(jobs).claimNext(eq("SYNC_START"), anyString(), eq(Duration.ofSeconds(30)));
        verify(jobs).claimNextRouted(eq("SYNC_RESUME"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(true));
        verify(jobs).claimNextRouted(eq("SYNC_CANCEL"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(true));
        verify(jobs).claimNextRouted(eq("SYNC_PAUSE"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
        verify(jobs).claimNextRouted(eq("SYNC_FINISH"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
        verify(jobs).claimNextRouted(eq("SYNC_RATE_LIMIT"), anyString(), eq("worker-1"),
                eq(Duration.ofSeconds(30)), eq(false));
    }
}
