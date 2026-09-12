package io.github.redisops.application.governance;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.governance.*;
import io.github.redisops.domain.job.JobRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CleanupGovernanceService {
    private final CleanupGovernanceRepository tasks;
    private final ClusterRepository clusters;
    private final JobRepository jobs;
    private final AuditRepository audits;
    public CleanupGovernanceService(CleanupGovernanceRepository tasks, ClusterRepository clusters, JobRepository jobs,
            AuditRepository audits) {
        this.tasks = tasks;
        this.clusters = clusters;
        this.jobs = jobs;
        this.audits = audits;
    }
    @Transactional
    public CleanupGovernanceTask create(long clusterId, Integer database, String pattern, long impactLimit, int rate,
            String operator) {
        RedisCluster cluster = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        if (cluster.status() != ClusterStatus.ACTIVE)
            throw new BusinessException("CLEANUP_CLUSTER_UNAVAILABLE", "cluster must be active");
        int db = cluster.mode() == ClusterMode.CLUSTER ? 0 : database == null ? 0 : database;
        if (cluster.mode() == ClusterMode.CLUSTER && database != null && database != 0)
            throw new BusinessException("CLEANUP_INVALID_DB", "cluster database must be 0");
        if (impactLimit < 1 || impactLimit > 10_000_000)
            throw new BusinessException("CLEANUP_INVALID_LIMIT", "impact limit must be between 1 and 10000000");
        var saved = tasks.saveTask(new CleanupGovernanceTask(null,
                "CLEAN-" + UUID.randomUUID().toString().substring(0, 12), clusterId, db,
                pattern == null || pattern.isBlank() ? "*" : pattern, impactLimit, Math.min(100_000, Math.max(1, rate)),
                TtlGovernanceStatus.CREATED, TtlApprovalStatus.PENDING, null, 0, Instant.now(), Instant.now()));
        audits.append(operator, "CLEANUP_GOVERNANCE_CREATE", "CLEANUP_GOVERNANCE", saved.id().toString(), "SUCCESS");
        return saved;
    }
    public List<CleanupGovernanceTask> list() {
        return tasks.findTasks();
    }
    public CleanupGovernanceTask get(long id) {
        return tasks.findTask(id).orElseThrow(() -> BusinessException.notFound("cleanupGovernanceTask", id));
    }
    public Optional<CleanupGovernanceRun> latest(long id) {
        return tasks.latestRun(id);
    }
    public List<CleanupGovernanceCheckpoint> checkpoints(long id) {
        return latest(id).map(x -> tasks.checkpoints(x.id())).orElse(List.of());
    }
    @Transactional
    public CleanupGovernanceTask dryRun(long id, long version, String operator, String key) {
        var current = get(id);
        if (!Set.of(TtlGovernanceStatus.CREATED, TtlGovernanceStatus.FAILED).contains(current.status()))
            throw new BusinessException("CLEANUP_INVALID_STATE", "task must be created or failed before dry run");
        var next = transition(id, version, TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, null);
        jobs.enqueue("CLEANUP_GOVERNANCE", id, "{\"taskId\":" + id + ",\"action\":\"DRY_RUN\"}", key);
        audits.append(operator, "CLEANUP_GOVERNANCE_DRY_RUN", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public CleanupGovernanceTask approve(long id, long version, String operator, String note) {
        if (note == null || note.isBlank())
            throw new BusinessException("CLEANUP_CONFIRMATION_REQUIRED", "approval note is required");
        if (get(id).status() != TtlGovernanceStatus.AWAITING_APPROVAL)
            throw new BusinessException("CLEANUP_NOT_READY", "dry run must complete before approval");
        var next = transition(id, version, TtlGovernanceStatus.APPROVED, TtlApprovalStatus.APPROVED, note);
        audits.append(operator, "CLEANUP_GOVERNANCE_APPROVE", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public CleanupGovernanceTask start(long id, long version, String operator, String key) {
        var current = get(id);
        if (current.status() != TtlGovernanceStatus.APPROVED && current.status() != TtlGovernanceStatus.PAUSED)
            throw new BusinessException("CLEANUP_APPROVAL_REQUIRED", "approved dry run is required");
        var next = transition(id, version, TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED,
                current.approvalNote());
        jobs.enqueue("CLEANUP_GOVERNANCE", id, "{\"taskId\":" + id + ",\"action\":\"APPLY\"}", key);
        audits.append(operator, "CLEANUP_GOVERNANCE_START", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public CleanupGovernanceTask pause(long id, long version, String operator) {
        if (get(id).status() != TtlGovernanceStatus.RUNNING)
            throw new BusinessException("CLEANUP_NOT_RUNNING", "only a running task can be paused");
        var next = transition(id, version, TtlGovernanceStatus.PAUSED, TtlApprovalStatus.APPROVED,
                get(id).approvalNote());
        audits.append(operator, "CLEANUP_GOVERNANCE_PAUSE", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public CleanupGovernanceTask cancel(long id, long version, String operator) {
        var current = get(id);
        if (Set.of(TtlGovernanceStatus.COMPLETED, TtlGovernanceStatus.CANCELLED).contains(current.status()))
            throw new BusinessException("CLEANUP_NOT_ACTIVE", "cleanup task is already finished");
        var next = transition(id, version, TtlGovernanceStatus.CANCELLED, current.approvalStatus(),
                current.approvalNote());
        audits.append(operator, "CLEANUP_GOVERNANCE_CANCEL", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    public CleanupGovernanceTask transition(long id, long version, TtlGovernanceStatus status,
            TtlApprovalStatus approval, String note) {
        var c = get(id);
        var n = new CleanupGovernanceTask(c.id(), c.taskNo(), c.clusterId(), c.databaseNo(), c.includePattern(),
                c.impactLimit(), c.scanRatePerSecond(), status, approval, note, version, c.createdAt(), Instant.now());
        if (!tasks.updateTask(n, version))
            throw new BusinessException("VERSION_CONFLICT", "cleanup task changed");
        return get(id);
    }
}
