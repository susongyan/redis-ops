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
public class TtlGovernanceService {
    private final TtlGovernanceRepository tasks;
    private final ClusterRepository clusters;
    private final JobRepository jobs;
    private final AuditRepository audits;

    public TtlGovernanceService(TtlGovernanceRepository tasks, ClusterRepository clusters, JobRepository jobs,
            AuditRepository audits) {
        this.tasks = tasks;
        this.clusters = clusters;
        this.jobs = jobs;
        this.audits = audits;
    }

    @Transactional
    public TtlGovernanceTask create(long clusterId, Integer database, String pattern, long ttl, int rate, long max,
            String operator) {
        RedisCluster cluster = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        if (cluster.status() != ClusterStatus.ACTIVE)
            throw new BusinessException("GOVERNANCE_CLUSTER_UNAVAILABLE", "cluster must be active");
        int db = cluster.mode() == ClusterMode.CLUSTER ? 0 : database == null ? 0 : database;
        if (cluster.mode() == ClusterMode.CLUSTER && database != null && database != 0)
            throw new BusinessException("GOVERNANCE_INVALID_DB", "cluster database must be 0");
        if (ttl < 1 || ttl > 31_536_000)
            throw new BusinessException("GOVERNANCE_INVALID_TTL", "target TTL must be between 1 second and 365 days");
        var saved = tasks.saveTask(new TtlGovernanceTask(null, "TTL-" + UUID.randomUUID().toString().substring(0, 12),
                clusterId, db, pattern == null || pattern.isBlank() ? "*" : pattern, ttl,
                Math.min(100_000, Math.max(1, rate)), Math.min(10_000_000, Math.max(1, max)),
                TtlGovernanceStatus.CREATED, TtlApprovalStatus.PENDING, 0, Instant.now(), Instant.now()));
        audits.append(operator, "TTL_GOVERNANCE_CREATE", "TTL_GOVERNANCE", saved.id().toString(), "SUCCESS");
        return saved;
    }
    public List<TtlGovernanceTask> list() {
        return tasks.findTasks();
    }
    public TtlGovernanceTask get(long id) {
        return tasks.findTask(id).orElseThrow(() -> BusinessException.notFound("ttlGovernanceTask", id));
    }
    public Optional<TtlGovernanceRun> latest(long id) {
        return tasks.latestRun(id);
    }
    public List<TtlGovernanceCheckpoint> checkpoints(long id) {
        return latest(id).map(x -> tasks.checkpoints(x.id())).orElse(List.of());
    }

    @Transactional
    public TtlGovernanceTask dryRun(long id, long version, String operator, String idempotencyKey) {
        TtlGovernanceTask current = get(id);
        if (!Set.of(TtlGovernanceStatus.CREATED, TtlGovernanceStatus.FAILED).contains(current.status()))
            throw new BusinessException("GOVERNANCE_INVALID_STATE", "task must be created or failed before dry run");
        TtlGovernanceTask task = transition(id, version, TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING);
        jobs.enqueue("TTL_GOVERNANCE", id, "{\"taskId\":" + id + ",\"action\":\"DRY_RUN\"}", idempotencyKey);
        audits.append(operator, "TTL_GOVERNANCE_DRY_RUN", "TTL_GOVERNANCE", Long.toString(id), "SUCCESS");
        return task;
    }
    @Transactional
    public TtlGovernanceTask approve(long id, long version, String operator) {
        TtlGovernanceTask task = get(id);
        if (task.status() != TtlGovernanceStatus.AWAITING_APPROVAL)
            throw new BusinessException("GOVERNANCE_NOT_READY", "dry run must complete before approval");
        TtlGovernanceTask next = transition(id, version, TtlGovernanceStatus.APPROVED, TtlApprovalStatus.APPROVED);
        audits.append(operator, "TTL_GOVERNANCE_APPROVE", "TTL_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public TtlGovernanceTask start(long id, long version, String operator, String key) {
        TtlGovernanceTask task = get(id);
        if ((!Set.of(TtlGovernanceStatus.APPROVED, TtlGovernanceStatus.PAUSED).contains(task.status()))
                || task.approvalStatus() != TtlApprovalStatus.APPROVED)
            throw new BusinessException("GOVERNANCE_APPROVAL_REQUIRED", "approved dry run is required");
        TtlGovernanceTask next = transition(id, version, TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED);
        jobs.enqueue("TTL_GOVERNANCE", id, "{\"taskId\":" + id + ",\"action\":\"APPLY\"}", key);
        audits.append(operator, "TTL_GOVERNANCE_START", "TTL_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public TtlGovernanceTask pause(long id, long version, String operator) {
        if (get(id).status() != TtlGovernanceStatus.RUNNING)
            throw new BusinessException("GOVERNANCE_NOT_RUNNING", "only a running task can be paused");
        TtlGovernanceTask next = transition(id, version, TtlGovernanceStatus.PAUSED, TtlApprovalStatus.APPROVED);
        audits.append(operator, "TTL_GOVERNANCE_PAUSE", "TTL_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    @Transactional
    public TtlGovernanceTask cancel(long id, long version, String operator) {
        TtlGovernanceTask task = get(id);
        if (Set.of(TtlGovernanceStatus.COMPLETED, TtlGovernanceStatus.CANCELLED).contains(task.status()))
            throw new BusinessException("GOVERNANCE_NOT_ACTIVE", "governance task is already finished");
        TtlGovernanceTask next = transition(id, version, TtlGovernanceStatus.CANCELLED, task.approvalStatus());
        audits.append(operator, "TTL_GOVERNANCE_CANCEL", "TTL_GOVERNANCE", Long.toString(id), "SUCCESS");
        return next;
    }
    public TtlGovernanceTask transition(long id, long version, TtlGovernanceStatus status, TtlApprovalStatus approval) {
        TtlGovernanceTask current = get(id);
        var next = new TtlGovernanceTask(current.id(), current.taskNo(), current.clusterId(), current.databaseNo(),
                current.includePattern(), current.targetTtlSeconds(), current.scanRatePerSecond(), current.maxKeys(),
                status, approval, version, current.createdAt(), Instant.now());
        if (!tasks.updateTask(next, version))
            throw new BusinessException("VERSION_CONFLICT", "governance task changed");
        return get(id);
    }
}
