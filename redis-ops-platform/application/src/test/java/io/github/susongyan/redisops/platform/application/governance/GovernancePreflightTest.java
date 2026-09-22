package io.github.susongyan.redisops.platform.application.governance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernancePreflightTest {
    final JobRepository jobs = mock(JobRepository.class);
    final AuditRepository audits = mock(AuditRepository.class);
    final TtlGovernanceRepository ttlRepo = mock(TtlGovernanceRepository.class);
    final CleanupGovernanceRepository cleanupRepo = mock(CleanupGovernanceRepository.class);
    final AtomicReference<TtlGovernanceTask> ttl = new AtomicReference<>();
    final AtomicReference<CleanupGovernanceTask> cleanup = new AtomicReference<>();
    final TtlGovernanceService ttlService = new TtlGovernanceService(ttlRepo, mock(ClusterRepository.class), jobs,
            audits);
    final CleanupGovernanceService cleanupService = new CleanupGovernanceService(cleanupRepo,
            mock(ClusterRepository.class), jobs, audits);
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
    void bothSkipAuthorizeAndEnqueueApplyWithoutReusingPreflight() {
        ttlService.skipDryRunAndStart(1, 0, "user:1", "CHG-123 business confirmed", "ttl");
        cleanupService.skipDryRunAndStart(2, 0, "user:1", "CHG-123 business confirmed", "cleanup");
        assertEquals(TtlGovernanceStatus.RUNNING, ttl.get().status());
        assertEquals(TtlGovernanceStatus.RUNNING, cleanup.get().status());
        assertEquals(TtlApprovalStatus.APPROVED, ttl.get().approvalStatus());
        assertEquals(TtlApprovalStatus.APPROVED, cleanup.get().approvalStatus());
        verify(jobs).enqueue(eq("TTL_GOVERNANCE"), eq(1L), contains("\"resume\":false"), eq("ttl"));
        verify(jobs).enqueue(eq("CLEANUP_GOVERNANCE"), eq(2L), contains("\"version\":1"), eq("cleanup"));
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
        verify(jobs).enqueue(eq("TTL_GOVERNANCE"), eq(1L), contains("\"resume\":true"), eq("ttl"));
        verify(jobs).enqueue(eq("CLEANUP_GOVERNANCE"), eq(2L), contains("\"action\":\"DRY_RUN\""), eq("cleanup"));
        assertEquals(TtlApprovalStatus.PENDING, cleanup.get().approvalStatus());
    }
    @Test
    void skipPausedPreflightRequiresPreviousJobToExit() {
        ttl.set(ttlTask(TtlGovernanceStatus.DRY_RUN_PAUSED, TtlApprovalStatus.PENDING, 2));
        cleanup.set(cleanupTask(TtlGovernanceStatus.DRY_RUN_PAUSED, TtlApprovalStatus.PENDING, 2));
        when(jobs.hasExecuting(anyString(), anyLong())).thenReturn(true);
        assertThrows(BusinessException.class,
                () -> ttlService.skipDryRunAndStart(1, 2, "user:1", "approved scope", "job-key"));
        assertThrows(BusinessException.class, () -> cleanupService.dryRun(2, 2, "user:1", "resume"));
        when(jobs.hasExecuting(anyString(), anyLong())).thenReturn(false);
        ttlService.skipDryRunAndStart(1, 2, "user:1", "approved scope", "job-key");
        cleanupService.skipDryRunAndStart(2, 2, "user:1", "approved scope", "job-key");
        assertEquals(TtlGovernanceStatus.RUNNING, cleanup.get().status());
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
    void auditSnapshotDoesNotContainRuleText() {
        String details = GovernanceAudit.details(ttl.get());
        assertTrue(details.contains("SHA-256"));
        assertFalse(details.contains("private-pattern"));
        assertFalse(GovernanceAudit.details(cleanup.get()).contains("private-pattern"));
        assertTrue(GovernanceAudit.skip(TtlGovernanceStatus.CREATED, "CHG-123").contains("CHG-123"));
    }
}
