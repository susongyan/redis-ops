package io.github.susongyan.redisops.platform.application.governance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import io.github.susongyan.redisops.platform.domain.validation.RedisValidationPort;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernancePreflightTest {
    final LocalJobRunner runner = mock(LocalJobRunner.class);
    final AuditRepository audits = mock(AuditRepository.class);
    final TtlGovernanceRepository ttlRepo = mock(TtlGovernanceRepository.class);
    final CleanupGovernanceRepository cleanupRepo = mock(CleanupGovernanceRepository.class);
    final AtomicReference<TtlGovernanceTask> ttl = new AtomicReference<>();
    final AtomicReference<CleanupGovernanceTask> cleanup = new AtomicReference<>();
    final TtlGovernanceService ttlService = new TtlGovernanceService(ttlRepo, mock(ClusterRepository.class), audits,
            runner, mock(RedisValidationPort.class), mock(ReadOnlyScanPort.class), mock(AlertService.class));
    final CleanupGovernanceService cleanupService = new CleanupGovernanceService(cleanupRepo,
            mock(ClusterRepository.class), audits, runner, mock(RedisValidationPort.class),
            mock(ReadOnlyScanPort.class),
            mock(AlertService.class));
    TtlGovernanceTask ttlTask(TtlGovernanceStatus state, TtlApprovalStatus approval, long version) {
        return new TtlGovernanceTask(1L, "TTL-1", 10, 0, "private-pattern:*", 60, 100, 1000, state, approval, version,
                Instant.EPOCH, Instant.EPOCH);
    }
    CleanupGovernanceTask cleanupTask(TtlGovernanceStatus state, TtlApprovalStatus approval, long version) {
        return new CleanupGovernanceTask(2L, "CLEAN-2", 10, 0, "private-pattern:*", 1000, 100, state, approval, null,
                version, Instant.EPOCH, Instant.EPOCH);
    }
    @BeforeEach
    void setup() {
        ttl.set(ttlTask(TtlGovernanceStatus.CREATED, TtlApprovalStatus.PENDING, 0));
        cleanup.set(cleanupTask(TtlGovernanceStatus.CREATED, TtlApprovalStatus.PENDING, 0));
        when(ttlRepo.findTask(1)).thenAnswer(i -> Optional.of(ttl.get()));
        when(cleanupRepo.findTask(2)).thenAnswer(i -> Optional.of(cleanup.get()));
        when(ttlRepo.findRecentlyActiveRun(eq(1L), anyInt())).thenReturn(Optional.empty());
        when(cleanupRepo.findRecentlyActiveRun(eq(2L), anyInt())).thenReturn(Optional.empty());
        when(ttlRepo.updateTask(any(), anyLong())).thenAnswer(i -> {
            TtlGovernanceTask task = i.getArgument(0);
            if (ttl.get().version() != (long) i.getArgument(1))
                return false;
            ttl.set(ttlTask(task.status(), task.approvalStatus(), task.version() + 1));
            return true;
        });
        when(cleanupRepo.updateTask(any(), anyLong())).thenAnswer(i -> {
            CleanupGovernanceTask task = i.getArgument(0);
            if (cleanup.get().version() != (long) i.getArgument(1))
                return false;
            cleanup.set(cleanupTask(task.status(), task.approvalStatus(), task.version() + 1));
            return true;
        });
    }
    @Test
    void bothSkipAuthorizeAndStartApplyWithoutReusingPreflight() {
        ttlService.skipDryRunAndStart(1, 0, "user:1", "CHG-123 business confirmed", "ttl");
        cleanupService.skipDryRunAndStart(2, 0, "user:1", "CHG-123 business confirmed", "cleanup");
        assertEquals(TtlGovernanceStatus.RUNNING, ttl.get().status());
        assertEquals(TtlGovernanceStatus.RUNNING, cleanup.get().status());
        assertEquals(TtlApprovalStatus.APPROVED, ttl.get().approvalStatus());
        assertEquals(TtlApprovalStatus.APPROVED, cleanup.get().approvalStatus());
        // apply always starts from a fresh run: resume=false is baked into the submitted execution
        verify(runner).submitAfterCommit(eq("TTL_GOVERNANCE"), any(Runnable.class));
        verify(runner).submitAfterCommit(eq("CLEANUP_GOVERNANCE"), any(Runnable.class));
        verify(audits).append(eq("user:1"), eq("TTL_GOVERNANCE_SKIP_DRY_RUN"), anyString(), eq("1"), eq("SUCCESS"),
                contains("CHG-123"));
        verify(audits).append(eq("user:1"), eq("CLEANUP_GOVERNANCE_EXECUTION_AUTHORIZED"), anyString(), eq("2"),
                eq("SUCCESS"), anyString());
    }
    @Test
    void bothPauseAndResumeStayReadOnly() {
        ttl.set(ttlTask(TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, 1));
        cleanup.set(cleanupTask(TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, 1));
        ttlService.pause(1, 1, "user:1");
        cleanupService.pause(2, 1, "user:1");
        assertEquals(TtlGovernanceStatus.DRY_RUN_PAUSED, ttl.get().status());
        assertEquals(TtlGovernanceStatus.DRY_RUN_PAUSED, cleanup.get().status());
        assertThrows(BusinessException.class, () -> ttlService.start(1, 2, "user:1", "k"));
        assertThrows(BusinessException.class, () -> cleanupService.start(2, 2, "user:1", "k"));
        ttlService.dryRun(1, 2, "user:1", "ttl");
        cleanupService.dryRun(2, 2, "user:1", "cleanup");
        verify(runner).submitAfterCommit(eq("TTL_GOVERNANCE"), any(Runnable.class));
        verify(runner).submitAfterCommit(eq("CLEANUP_GOVERNANCE"), any(Runnable.class));
        assertEquals(TtlApprovalStatus.PENDING, cleanup.get().approvalStatus());
    }
    @Test
    void skipPausedPreflightRequiresPreviousRunToExit() {
        ttl.set(ttlTask(TtlGovernanceStatus.DRY_RUN_PAUSED, TtlApprovalStatus.PENDING, 2));
        cleanup.set(cleanupTask(TtlGovernanceStatus.DRY_RUN_PAUSED, TtlApprovalStatus.PENDING, 2));
        TtlGovernanceRun exiting = new TtlGovernanceRun(9L, 1L, "TRUN-9", TtlGovernanceStatus.DRY_RUN, 10, 3, 1, 0, 0,
                0, Instant.now(), null, null);
        CleanupGovernanceRun exitingCleanup = new CleanupGovernanceRun(9L, 2L, "CRUN-9", TtlGovernanceStatus.DRY_RUN,
                10, 3, 1, 0, 0, 0, Instant.now(), null, null);
        when(ttlRepo.findRecentlyActiveRun(eq(1L), anyInt())).thenReturn(Optional.of(exiting));
        when(cleanupRepo.findRecentlyActiveRun(eq(2L), anyInt())).thenReturn(Optional.of(exitingCleanup));
        assertEquals("REQUEST_IN_PROGRESS",
                assertThrows(BusinessException.class,
                        () -> ttlService.skipDryRunAndStart(1, 2, "user:1", "approved scope", "job-key")).code());
        assertThrows(BusinessException.class, () -> cleanupService.dryRun(2, 2, "user:1", "resume"));
        when(ttlRepo.findRecentlyActiveRun(eq(1L), anyInt())).thenReturn(Optional.empty());
        when(cleanupRepo.findRecentlyActiveRun(eq(2L), anyInt())).thenReturn(Optional.empty());
        ttlService.skipDryRunAndStart(1, 2, "user:1", "approved scope", "job-key");
        cleanupService.skipDryRunAndStart(2, 2, "user:1", "approved scope", "job-key");
        assertEquals(TtlGovernanceStatus.RUNNING, cleanup.get().status());
    }
    @Test
    void saturatedPoolRejectsStartBeforeTheStatusChange() {
        doThrow(new BusinessException("TTL_GOVERNANCE_BUSY", "pool full")).when(runner).checkCapacity("TTL_GOVERNANCE");
        assertEquals("TTL_GOVERNANCE_BUSY",
                assertThrows(BusinessException.class, () -> ttlService.dryRun(1, 0, "user:1", "k")).code());
        assertEquals(TtlGovernanceStatus.CREATED, ttl.get().status());
        verify(runner, never()).submitAfterCommit(anyString(), any(Runnable.class));
    }
    @Test
    void invalidReasonVersionAndActivePhaseAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ttlService.skipDryRunAndStart(1, 0, "user:1", " ", "job-key"));
        assertThrows(IllegalArgumentException.class,
                () -> cleanupService.skipDryRunAndStart(2, 0, "user:1", "x".repeat(501), "job-key"));
        assertThrows(BusinessException.class, () -> ttlService.skipDryRunAndStart(1, 9, "user:1", "reason", "job-key"));
        ttl.set(ttlTask(TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, 1));
        cleanup.set(cleanupTask(TtlGovernanceStatus.PAUSED, TtlApprovalStatus.APPROVED, 1));
        assertThrows(BusinessException.class, () -> ttlService.skipDryRunAndStart(1, 1, "user:1", "reason", "job-key"));
        assertThrows(BusinessException.class,
                () -> cleanupService.skipDryRunAndStart(2, 1, "user:1", "reason", "job-key"));
    }
    @Test
    void bothRejectExecutionWhenAnotherTaskRunsOnTheSameCluster() {
        when(ttlRepo.findActiveTaskForCluster(10L))
                .thenReturn(Optional.of(ttlTask(TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED, 9)));
        when(cleanupRepo.findActiveTaskForCluster(10L))
                .thenReturn(Optional.of(cleanupTask(TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED, 9)));
        assertEquals("GOVERNANCE_CLUSTER_BUSY",
                assertThrows(BusinessException.class, () -> ttlService.dryRun(1, 0, "user:1", "k")).code());
        assertEquals("GOVERNANCE_CLUSTER_BUSY",
                assertThrows(BusinessException.class, () -> ttlService.skipDryRunAndStart(1, 0, "user:1", "r", "k"))
                        .code());
        assertEquals("CLEANUP_CLUSTER_BUSY",
                assertThrows(BusinessException.class, () -> cleanupService.dryRun(2, 0, "user:1", "k")).code());
        assertEquals("CLEANUP_CLUSTER_BUSY",
                assertThrows(BusinessException.class,
                        () -> cleanupService.skipDryRunAndStart(2, 0, "user:1", "r", "k")).code());
    }

    @Test
    void auditSnapshotDoesNotContainRuleText() {
        String details = GovernanceAudit.details(ttl.get());
        assertTrue(details.contains("SHA-256"));
        assertFalse(details.contains("private-pattern"));
        assertFalse(GovernanceAudit.details(cleanup.get()).contains("private-pattern"));
        assertTrue(GovernanceAudit.skip(TtlGovernanceStatus.CREATED, "CHG-123").contains("CHG-123"));
    }
}
