package io.github.susongyan.redisops.platform.application.governance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import io.github.susongyan.redisops.platform.domain.validation.RedisValidationPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The governance execution loop runs in-process on the service (no async-job worker anymore). */
class GovernanceExecutionTest {
    final RedisValidationPort redis = mock(RedisValidationPort.class);
    final ReadOnlyScanPort reads = mock(ReadOnlyScanPort.class);
    final ReadOnlyScanPort.Session session = mock(ReadOnlyScanPort.Session.class);
    final ReadOnlyScanPort.Shard replica = new ReadOnlyScanPort.Shard("s1", "replica:6379", "replica", "run", "node");
    final ReadOnlyScanPort.Topology topology = new ReadOnlyScanPort.Topology("fingerprint", List.of(replica));
    final String checkpointId = topology.checkpointId(replica);
    final TtlGovernanceRepository ttlRepo = mock(TtlGovernanceRepository.class);
    final CleanupGovernanceRepository cleanupRepo = mock(CleanupGovernanceRepository.class);
    final AlertService alerts = mock(AlertService.class);
    final TtlGovernanceService ttlService = new TtlGovernanceService(ttlRepo, mock(ClusterRepository.class),
            mock(AuditRepository.class), mock(io.github.susongyan.redisops.platform.application.LocalJobRunner.class),
            redis, reads, alerts);
    final CleanupGovernanceService cleanupService = new CleanupGovernanceService(cleanupRepo,
            mock(ClusterRepository.class), mock(AuditRepository.class),
            mock(io.github.susongyan.redisops.platform.application.LocalJobRunner.class), redis, reads, alerts);
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
    @BeforeEach
    void setup() {
        when(reads.topology(10, 0)).thenReturn(topology);
        when(reads.count(eq(10L), eq(0), any())).thenReturn(100L);
        when(redis.countKeys(10, 0)).thenReturn(100L);
        when(reads.open(10, 0, replica)).thenReturn(session);
        when(session.scan(anyString(), eq(2000)))
                .thenReturn(new ReadOnlyScanPort.Page("0", List.of(new byte[]{1})));
        when(session.ttlBatch(anyList())).thenReturn(List.of(-1L));
        when(redis.scanShards(10, 0)).thenReturn(List.of(new RedisValidationPort.ScanShard("s1", "10.0.0.1:6379")));
        when(redis.scan(eq(10L), eq(0), eq("s1"), anyString(), eq(2000)))
                .thenReturn(new RedisValidationPort.ScanPage("0",
                        List.of(new RedisValidationPort.ValidationKey(new byte[]{1}))));
        when(redis.applyTtlIfUnchangedBatch(eq(10L), eq(0), anyList(), eq(-1L), eq(60L)))
                .thenReturn(List.of(new RedisValidationPort.TtlApplyResult(-1, true, false)));
        when(redis.unlinkIfPresentBatch(eq(10L), eq(0), anyList())).thenReturn(List.of(true));
        when(ttlRepo.updateTask(any(), anyLong())).thenReturn(true);
        when(cleanupRepo.updateTask(any(), anyLong())).thenReturn(true);
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
    }
    @Test
    void dryRunNeverWritesRedis() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)));
        when(cleanupRepo.findTask(1)).thenReturn(Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN, 1)));
        ttlService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, false);
        cleanupService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, false);
        verify(redis, never()).applyTtlIfUnchangedBatch(anyLong(), anyInt(), anyList(), anyLong(), anyLong());
        verify(redis, never()).unlinkIfPresentBatch(anyLong(), anyInt(), anyList());
        verifyNoInteractions(redis);
        verify(session, times(2)).scan("0", 2000);
        verify(ttlRepo).saveRun(argThat(r -> r.candidateKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN
                && r.scannedKeys() == 1));
        verify(cleanupRepo).saveRun(argThat(
                r -> r.candidateKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN && r.scannedKeys() == 1));
        // A finished run must close shard rows: cap-limited scans end with the last batch write
        // still on RUNNING, which used to leave the drawer showing 执行中 after completion.
        verify(ttlRepo).finalizeCheckpoints(20L, TtlGovernanceStatus.COMPLETED);
        verify(cleanupRepo).finalizeCheckpoints(20L, TtlGovernanceStatus.COMPLETED);
    }
    @Test
    void staleCommandCannotScanOrChangeNewTaskPhase() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)));
        when(cleanupRepo.findTask(1)).thenReturn(Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN, 1)));
        ttlService.execute(1, 0, TtlGovernanceStatus.DRY_RUN, false);
        cleanupService.execute(1, 0, TtlGovernanceStatus.DRY_RUN, false);
        verifyNoInteractions(redis, reads);
        verify(ttlRepo, never()).updateTask(any(), anyLong());
        verify(cleanupRepo, never()).updateTask(any(), anyLong());
        verify(ttlRepo, never()).saveRun(any());
        verify(cleanupRepo, never()).saveRun(any());
    }
    @Test
    void resumePreflightUsesSavedCursorAndCounters() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)));
        when(cleanupRepo.findTask(1)).thenReturn(Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN, 1)));
        when(ttlRepo.latestRun(1)).thenReturn(Optional.of(new TtlGovernanceRun(9L, 1, "TRUN-4",
                TtlGovernanceStatus.DRY_RUN, 100, 7, 3, 0, 4, 0, Instant.EPOCH, null, null)));
        when(cleanupRepo.latestRun(1)).thenReturn(Optional.of(new CleanupGovernanceRun(9L, 1, "CRUN-4",
                TtlGovernanceStatus.DRY_RUN, 100, 7, 7, 0, 0, 0, Instant.EPOCH, null, null)));
        var ttlCheckpoint = new TtlGovernanceCheckpoint(9, checkpointId, "42", 7, TtlGovernanceStatus.RUNNING,
                Instant.EPOCH);
        var cleanupCheckpoint = new CleanupGovernanceCheckpoint(9, checkpointId, "42", 7, TtlGovernanceStatus.RUNNING,
                Instant.EPOCH);
        when(ttlRepo.checkpoints(9)).thenReturn(List.of(ttlCheckpoint));
        when(cleanupRepo.checkpoints(9)).thenReturn(List.of(cleanupCheckpoint));
        when(ttlRepo.checkpoint(9, checkpointId)).thenReturn(Optional.of(ttlCheckpoint));
        when(cleanupRepo.checkpoint(9, checkpointId)).thenReturn(Optional.of(cleanupCheckpoint));
        ttlService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, true);
        cleanupService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, true);
        verify(session, times(2)).scan("42", 2000);
        verify(ttlRepo).saveRun(argThat(r -> r.id() == 9 && r.scannedKeys() == 8
                && r.status() == TtlGovernanceStatus.DRY_RUN));
        verify(cleanupRepo).saveRun(argThat(r -> r.id() == 9 && r.scannedKeys() == 8
                && r.status() == TtlGovernanceStatus.DRY_RUN));
    }
    @Test
    void applyAfterSkipStartsFreshInsteadOfReusingPreflight() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.RUNNING, 1)));
        when(cleanupRepo.findTask(1)).thenReturn(Optional.of(cleanup(TtlGovernanceStatus.RUNNING, 1)));
        when(ttlRepo.latestRun(1)).thenReturn(Optional.of(new TtlGovernanceRun(9L, 1, "TRUN-4",
                TtlGovernanceStatus.DRY_RUN, 1000, 1000, 1000, 0, 0, 0, Instant.EPOCH, null, null)));
        when(cleanupRepo.latestRun(1)).thenReturn(Optional.of(new CleanupGovernanceRun(9L, 1, "CRUN-4",
                TtlGovernanceStatus.DRY_RUN, 1000, 1000, 1000, 0, 0, 0, Instant.EPOCH, null, null)));
        ttlService.execute(1, 1, TtlGovernanceStatus.RUNNING, false);
        cleanupService.execute(1, 1, TtlGovernanceStatus.RUNNING, false);
        verify(redis, times(2)).scan(10, 0, "s1", "0", 2000);
        verify(redis).applyTtlIfUnchangedBatch(eq(10L), eq(0), anyList(), eq(-1L), eq(60L));
        verify(redis).unlinkIfPresentBatch(eq(10L), eq(0), anyList());
        verifyNoInteractions(reads);
        verify(ttlRepo).saveRun(argThat(r -> r.id() != null && r.id() == 20 && r.scannedKeys() == 1
                && r.appliedKeys() == 1 && r.status() == TtlGovernanceStatus.RUNNING));
        verify(cleanupRepo).saveRun(argThat(r -> r.id() != null && r.id() == 20 && r.scannedKeys() == 1
                && r.deletedKeys() == 1 && r.status() == TtlGovernanceStatus.RUNNING));
    }
    @Test
    void pauseAtBatchBoundaryPersistsProgressWithoutApproving() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)),
                Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)),
                Optional.of(ttl(TtlGovernanceStatus.DRY_RUN_PAUSED, 2)));
        when(cleanupRepo.findTask(1)).thenReturn(Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN, 1)),
                Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN, 1)),
                Optional.of(cleanup(TtlGovernanceStatus.DRY_RUN_PAUSED, 2)));
        ttlService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, false);
        cleanupService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, false);
        verify(ttlRepo).saveRun(argThat(r -> r.scannedKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN));
        verify(cleanupRepo).saveRun(argThat(r -> r.scannedKeys() == 1 && r.status() == TtlGovernanceStatus.DRY_RUN));
        verify(ttlRepo, never()).updateTask(any(), anyLong());
        verify(cleanupRepo, never()).updateTask(any(), anyLong());
    }
    @Test
    void changedReplicaCannotReusePreflightCursorAndFailsClosed() {
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(ttl(TtlGovernanceStatus.DRY_RUN, 1)));
        when(ttlRepo.latestRun(1))
                .thenReturn(Optional.of(new TtlGovernanceRun(9L, 1, "TRUN-4", TtlGovernanceStatus.DRY_RUN,
                        100, 7, 3, 0, 4, 0, Instant.EPOCH, null, null)));
        when(ttlRepo.checkpoints(9)).thenReturn(List.of(new TtlGovernanceCheckpoint(9, "old-node", "42", 7,
                TtlGovernanceStatus.RUNNING, Instant.EPOCH)));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ttlService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, true));
        assertEquals("SCAN_TOPOLOGY_UNSTABLE", error.getMessage());
        verify(reads, never()).open(anyLong(), anyInt(), any());
        // fail-closed: run and task are failed, nothing is re-executed
        verify(ttlRepo).failRun(eq(9L), any(), eq("SCAN_TOPOLOGY_UNSTABLE"));
        verify(ttlRepo).updateTask(argThat(t -> t.status() == TtlGovernanceStatus.FAILED && t.version() == 1),
                eq(1L));
        verify(alerts).trigger(eq("TTL_GOVERNANCE_FAILED"), eq("TTL_GOVERNANCE"), eq("1"), eq(1d), anyString());
    }
    @Test
    void heartbeatIsTouchedDuringLongRateLimitSleeps() {
        TtlGovernanceTask slow = new TtlGovernanceTask(1L, "TTL-1", 10, 0, "*", 60, 1, 1000,
                TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, 1, Instant.EPOCH, Instant.EPOCH);
        when(ttlRepo.findTask(1)).thenReturn(Optional.of(slow));
        ttlService.execute(1, 1, TtlGovernanceStatus.DRY_RUN, false);
        // rate=1/s and a page of 1 key means ~1s of sleep in 200ms steps; the run must stay heartbeating
        // so no other instance's recovery sweep can mistake it for a dead executor
        verify(ttlRepo, atLeastOnce()).touchRun(20L);
    }
}
