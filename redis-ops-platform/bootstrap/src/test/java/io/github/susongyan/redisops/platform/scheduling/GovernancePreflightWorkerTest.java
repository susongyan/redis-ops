package io.github.susongyan.redisops.platform.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.application.governance.*;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.job.*;
import io.github.susongyan.redisops.platform.domain.validation.RedisValidationPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernancePreflightWorkerTest {
    final JobRepository jobs = mock(JobRepository.class);
    final RedisValidationPort redis = mock(RedisValidationPort.class);
    final TtlGovernanceService ttlService = mock(TtlGovernanceService.class);
    final CleanupGovernanceService cleanupService = mock(CleanupGovernanceService.class);
    final TtlGovernanceRepository ttlRepo = mock(TtlGovernanceRepository.class);
    final CleanupGovernanceRepository cleanupRepo = mock(CleanupGovernanceRepository.class);
    final AlertService alerts = mock(AlertService.class);
    final TtlGovernanceJobWorker ttlWorker = new TtlGovernanceJobWorker(jobs, ttlService, ttlRepo, redis, alerts,
            "test");
    final CleanupGovernanceJobWorker cleanupWorker = new CleanupGovernanceJobWorker(jobs, cleanupService, cleanupRepo,
            redis, alerts, "test");
    TtlGovernanceTask ttl(TtlGovernanceStatus status, long version) {
        return new TtlGovernanceTask(1L, "TTL-1", 10, 0, "*", 60, 100000, 1000, status,
                status == TtlGovernanceStatus.RUNNING ? TtlApprovalStatus.APPROVED : TtlApprovalStatus.PENDING,
                version, Instant.EPOCH, Instant.EPOCH);
    }
    CleanupGovernanceTask cleanup(TtlGovernanceStatus status, long version) {
        return new CleanupGovernanceTask(1L, "CLEAN-1", 10, 0, "*", 1000, 100000, status,
                status == TtlGovernanceStatus.RUNNING ? TtlApprovalStatus.APPROVED : TtlApprovalStatus.PENDING,
                null, version, Instant.EPOCH, Instant.EPOCH);
    }
    void job(String action, boolean resume, long version) {
        when(jobs.claimNext(anyString(), anyString(), any())).thenReturn(Optional.of(new AsyncJob(5L, "test", 1,
                "{\"taskId\":1,\"version\":" + version + ",\"action\":\"" + action + "\",\"resume\":" + resume + "}",
                "RUNNING", "test", "owner", Instant.now().plusSeconds(600), 1, 3, null)));
    }
    @BeforeEach
    void setup() {
        when(jobs.renew(anyLong(), anyString(), any())).thenReturn(true);
        when(redis.scanShards(10, 0)).thenReturn(List.of(new RedisValidationPort.ScanShard("s1")));
        when(redis.scan(eq(10L), eq(0), eq("s1"), anyString(), eq(200)))
                .thenReturn(new RedisValidationPort.ScanPage("0",
                        List.of(new RedisValidationPort.ValidationKey(new byte[]{1}))));
        when(redis.inspect(eq(10L), eq(0), any(), any())).thenReturn(
                Optional.of(new RedisValidationPort.ValidationValue("string", 1, -1, null, "METADATA", null)));
        when(redis.applyTtlIfUnchanged(eq(10L), eq(0), any(), eq(-1L), eq(60L)))
                .thenReturn(new RedisValidationPort.TtlApplyResult(-1, true, false));
        when(redis.unlinkIfPresent(eq(10L), eq(0), any())).thenReturn(true);
        when(ttlService.get(1)).thenReturn(ttl(TtlGovernanceStatus.DRY_RUN, 1));
        when(cleanupService.get(1)).thenReturn(cleanup(TtlGovernanceStatus.DRY_RUN, 1));
        when(ttlRepo.saveRun(any())).thenAnswer(i -> {
            TtlGovernanceRun r = i.getArgument(0);
            return new TtlGovernanceRun(r.id() == null ? 20L : r.id(), r.taskId(), r.runNo(), r.status(),
                    r.plannedKeys(), r.scannedKeys(), r.candidateKeys(), r.appliedKeys(), r.skippedKeys(),
                    r.failedKeys(), r.startedAt(), r.completedAt(), r.errorCode());
        });
        when(cleanupRepo.saveRun(any())).thenAnswer(i -> {
            CleanupGovernanceRun r = i.getArgument(0);
            return new CleanupGovernanceRun(r.id() == null ? 20L : r.id(), r.taskId(), r.runNo(), r.status(),
                    r.plannedKeys(), r.scannedKeys(), r.candidateKeys(), r.deletedKeys(), r.skippedKeys(),
                    r.failedKeys(), r.startedAt(), r.completedAt(), r.errorCode());
        });
        when(ttlService.saveProgress(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(cleanupService.saveProgress(any(), any())).thenAnswer(i -> i.getArgument(0));
    }
    @Test
    void dryRunNeverWritesRedis() {
        job("DRY_RUN", false, 1);
        ttlWorker.poll();
        cleanupWorker.poll();
        verify(redis, never()).applyTtlIfUnchanged(anyLong(), anyInt(), any(), anyLong(), anyLong());
        verify(redis, never()).unlinkIfPresent(anyLong(), anyInt(), any());
        verify(ttlService)
                .saveProgress(argThat(r -> r.candidateKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN), any());
        verify(cleanupService)
                .saveProgress(argThat(r -> r.candidateKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN), any());
    }
    @Test
    void staleJobCannotScanOrChangeNewTaskPhase() {
        job("DRY_RUN", false, 0);
        ttlWorker.poll();
        cleanupWorker.poll();
        verifyNoInteractions(redis);
        verify(ttlService, never()).transition(anyLong(), anyLong(), any(), any());
        verify(cleanupService, never()).transition(anyLong(), anyLong(), any(), any(), any());
        verify(ttlService, never()).completeRun(any(), anyLong(), anyBoolean());
        verify(cleanupService, never()).completeRun(any(), anyLong(), anyBoolean());
    }
    @Test
    void resumePreflightUsesSavedCursorAndCounters() {
        job("DRY_RUN", true, 1);
        when(ttlRepo.latestRun(1)).thenReturn(Optional.of(new TtlGovernanceRun(9L, 1, "TRUN-4",
                TtlGovernanceStatus.DRY_RUN, 100, 7, 3, 0, 4, 0, Instant.EPOCH, null, null)));
        when(cleanupRepo.latestRun(1)).thenReturn(Optional.of(new CleanupGovernanceRun(9L, 1, "CRUN-4",
                TtlGovernanceStatus.DRY_RUN, 100, 7, 7, 0, 0, 0, Instant.EPOCH, null, null)));
        when(ttlRepo.checkpoint(9, "s1")).thenReturn(
                Optional.of(new TtlGovernanceCheckpoint(9, "s1", "42", 7, TtlGovernanceStatus.RUNNING, Instant.EPOCH)));
        when(cleanupRepo.checkpoint(9, "s1")).thenReturn(Optional
                .of(new CleanupGovernanceCheckpoint(9, "s1", "42", 7, TtlGovernanceStatus.RUNNING, Instant.EPOCH)));
        ttlWorker.poll();
        cleanupWorker.poll();
        verify(redis, times(2)).scan(10, 0, "s1", "42", 200);
        verify(ttlService).saveProgress(argThat(r -> r.id() == 9 && r.scannedKeys() == 8), any());
        verify(cleanupService).saveProgress(argThat(r -> r.id() == 9 && r.scannedKeys() == 8), any());
    }
    @Test
    void applyAfterSkipStartsFreshInsteadOfReusingPreflight() {
        job("APPLY", false, 1);
        when(ttlService.get(1)).thenReturn(ttl(TtlGovernanceStatus.RUNNING, 1));
        when(cleanupService.get(1)).thenReturn(cleanup(TtlGovernanceStatus.RUNNING, 1));
        when(ttlRepo.latestRun(1)).thenReturn(Optional.of(new TtlGovernanceRun(9L, 1, "TRUN-4",
                TtlGovernanceStatus.DRY_RUN, 1000, 1000, 1000, 0, 0, 0, Instant.EPOCH, null, null)));
        when(cleanupRepo.latestRun(1)).thenReturn(Optional.of(new CleanupGovernanceRun(9L, 1, "CRUN-4",
                TtlGovernanceStatus.DRY_RUN, 1000, 1000, 1000, 0, 0, 0, Instant.EPOCH, null, null)));
        ttlWorker.poll();
        cleanupWorker.poll();
        verify(redis, times(2)).scan(10, 0, "s1", "0", 200);
        verify(redis).applyTtlIfUnchanged(eq(10L), eq(0), any(), eq(-1L), eq(60L));
        verify(redis).unlinkIfPresent(eq(10L), eq(0), any());
        verify(ttlService).saveProgress(argThat(r -> r.scannedKeys() == 1 && r.appliedKeys() == 1), any());
        verify(cleanupService).saveProgress(argThat(r -> r.scannedKeys() == 1 && r.deletedKeys() == 1), any());
    }
    @Test
    void pauseAtBatchBoundaryPersistsProgressWithoutApproving() {
        job("DRY_RUN", false, 1);
        when(ttlService.get(1)).thenReturn(ttl(TtlGovernanceStatus.DRY_RUN, 1), ttl(TtlGovernanceStatus.DRY_RUN, 1),
                ttl(TtlGovernanceStatus.DRY_RUN_PAUSED, 2));
        when(cleanupService.get(1)).thenReturn(cleanup(TtlGovernanceStatus.DRY_RUN, 1),
                cleanup(TtlGovernanceStatus.DRY_RUN, 1), cleanup(TtlGovernanceStatus.DRY_RUN_PAUSED, 2));
        ttlWorker.poll();
        cleanupWorker.poll();
        verify(ttlService).saveProgress(any(), any());
        verify(cleanupService).saveProgress(any(), any());
        verify(ttlService, never()).transition(anyLong(), anyLong(), any(), any());
        verify(cleanupService, never()).transition(anyLong(), anyLong(), any(), any(), any());
    }
    @Test
    void legacyPayloadIsNotSilentlyUpgradedToCurrentVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceJobCommand.parse("{\"taskId\":1,\"action\":\"APPLY\"}"));
    }
}
