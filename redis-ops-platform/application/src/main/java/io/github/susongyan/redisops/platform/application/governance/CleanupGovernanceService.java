package io.github.susongyan.redisops.platform.application.governance;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import io.github.susongyan.redisops.platform.domain.validation.RedisValidationPort;
import io.github.susongyan.redisops.platform.domain.validation.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Governance executes on the instance that received the request (no async-job queue left to take
 * over), fails closed on crash, and never resumes automatically: a human restarts the phase.
 */
@Service
public class CleanupGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(CleanupGovernanceService.class);
    private static final Duration STALE_RUN = Duration.ofMinutes(10);
    private static final Duration STALE_QUEUED = Duration.ofMinutes(30);
    private static final int PAGE = 2000;
    private static final int PREFLIGHT_PARALLELISM = 4;
    private static final Duration PERSIST_INTERVAL = Duration.ofSeconds(2);
    private final CleanupGovernanceRepository tasks;
    private final ClusterRepository clusters;
    private final AuditRepository audits;
    private final LocalJobRunner runner;
    private final RedisValidationPort redis;
    private final ReadOnlyScanPort reads;
    private final AlertService alerts;
    private final TransactionTemplate pages;
    public CleanupGovernanceService(CleanupGovernanceRepository tasks, ClusterRepository clusters,
            AuditRepository audits,
            LocalJobRunner runner, RedisValidationPort redis, ReadOnlyScanPort reads, AlertService alerts) {
        this(tasks, clusters, audits, runner, redis, reads, alerts, null);
    }
    @Autowired
    public CleanupGovernanceService(CleanupGovernanceRepository tasks, ClusterRepository clusters,
            AuditRepository audits,
            LocalJobRunner runner, RedisValidationPort redis, ReadOnlyScanPort reads, AlertService alerts,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.clusters = clusters;
        this.audits = audits;
        this.runner = runner;
        this.redis = redis;
        this.reads = reads;
        this.alerts = alerts;
        pages = manager == null ? null : new TransactionTemplate(manager);
    }
    /** Progress writes commit per page; the governance loop itself must not run in one long transaction. */
    private void inTransaction(Runnable work) {
        if (pages == null) {
            work.run();
            return;
        }
        pages.executeWithoutResult(status -> work.run());
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
        audits.append(operator, "CLEANUP_GOVERNANCE_CREATE", "CLEANUP_GOVERNANCE", saved.id().toString(), "SUCCESS",
                GovernanceAudit.details(saved));
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
    /** Newest-first run history so the drawer can hold the preflight result next to the apply run. */
    public List<CleanupGovernanceRun> runs(long id) {
        return tasks.findRuns(id);
    }
    public List<CleanupGovernanceCheckpoint> runCheckpoints(long runId) {
        return tasks.checkpoints(runId);
    }
    public List<CleanupGovernanceCheckpoint> checkpoints(long id) {
        return latest(id).map(x -> tasks.checkpoints(x.id())).orElse(List.of());
    }

    @Transactional
    public CleanupGovernanceTask dryRun(long id, long version, String operator, String key) {
        var current = get(id);
        if (!Set.of(TtlGovernanceStatus.CREATED, TtlGovernanceStatus.FAILED, TtlGovernanceStatus.DRY_RUN_PAUSED)
                .contains(current.status()))
            throw new BusinessException("CLEANUP_INVALID_STATE", "task must be created or failed before dry run");
        previousRunExiting(id);
        clusterIdle(current.clusterId());
        runner.checkCapacity("CLEANUP_GOVERNANCE");
        boolean resume = current.status() == TtlGovernanceStatus.DRY_RUN_PAUSED;
        var next = transition(id, version, TtlGovernanceStatus.DRY_RUN, TtlApprovalStatus.PENDING, null);
        long expectedVersion = next.version();
        runner.submitAfterCommit("CLEANUP_GOVERNANCE",
                () -> execute(id, expectedVersion, TtlGovernanceStatus.DRY_RUN, resume));
        audits.append(operator, "CLEANUP_GOVERNANCE_DRY_RUN", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(get(id)));
        return next;
    }
    @Transactional
    public CleanupGovernanceTask approve(long id, long version, String operator, String note) {
        if (note == null || note.isBlank())
            throw new BusinessException("CLEANUP_CONFIRMATION_REQUIRED", "approval note is required");
        if (get(id).status() != TtlGovernanceStatus.AWAITING_APPROVAL)
            throw new BusinessException("CLEANUP_NOT_READY",
                    "preflight must complete or be explicitly skipped before approval");
        var next = transition(id, version, TtlGovernanceStatus.APPROVED, TtlApprovalStatus.APPROVED, note);
        audits.append(operator, "CLEANUP_GOVERNANCE_APPROVE", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(get(id)));
        return next;
    }
    @Transactional
    public CleanupGovernanceTask start(long id, long version, String operator, String key) {
        var current = get(id);
        if ((current.status() != TtlGovernanceStatus.APPROVED && current.status() != TtlGovernanceStatus.PAUSED)
                || current.approvalStatus() != TtlApprovalStatus.APPROVED)
            throw new BusinessException("CLEANUP_APPROVAL_REQUIRED", "approved dry run is required");
        previousRunExiting(id);
        clusterIdle(current.clusterId());
        runner.checkCapacity("CLEANUP_GOVERNANCE");
        boolean resume = current.status() == TtlGovernanceStatus.PAUSED;
        var next = transition(id, version, TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED,
                current.approvalNote());
        long expectedVersion = next.version();
        runner.submitAfterCommit("CLEANUP_GOVERNANCE",
                () -> execute(id, expectedVersion, TtlGovernanceStatus.RUNNING, resume));
        audits.append(operator, "CLEANUP_GOVERNANCE_START", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(get(id)));
        return next;
    }
    @Transactional
    public CleanupGovernanceTask pause(long id, long version, String operator) {
        var current = get(id);
        if (!Set.of(TtlGovernanceStatus.RUNNING, TtlGovernanceStatus.DRY_RUN).contains(current.status()))
            throw new BusinessException("CLEANUP_NOT_RUNNING", "only a running task can be paused");
        var next = transition(id, version,
                current.status() == TtlGovernanceStatus.DRY_RUN
                        ? TtlGovernanceStatus.DRY_RUN_PAUSED
                        : TtlGovernanceStatus.PAUSED,
                current.approvalStatus(), current.approvalNote());
        audits.append(operator, "CLEANUP_GOVERNANCE_PAUSE", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(get(id)));
        return next;
    }
    @Transactional
    public CleanupGovernanceTask cancel(long id, long version, String operator) {
        var current = get(id);
        if (Set.of(TtlGovernanceStatus.COMPLETED, TtlGovernanceStatus.CANCELLED).contains(current.status()))
            throw new BusinessException("CLEANUP_NOT_ACTIVE", "cleanup task is already finished");
        var next = transition(id, version, TtlGovernanceStatus.CANCELLED, current.approvalStatus(),
                current.approvalNote());
        audits.append(operator, "CLEANUP_GOVERNANCE_CANCEL", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(get(id)));
        return next;
    }
    @Transactional
    public CleanupGovernanceTask skipDryRunAndStart(long id, long version, String operator, String reason, String key) {
        var current = get(id);
        if (!Set.of(TtlGovernanceStatus.CREATED, TtlGovernanceStatus.DRY_RUN_PAUSED).contains(current.status()))
            throw new BusinessException("CLEANUP_INVALID_STATE", "pause preflight before skipping");
        String details = GovernanceAudit.skip(current.status(), reason);
        previousRunExiting(id);
        clusterIdle(current.clusterId());
        runner.checkCapacity("CLEANUP_GOVERNANCE");
        var next = transition(id, version, TtlGovernanceStatus.RUNNING, TtlApprovalStatus.APPROVED, reason.trim());
        audits.append(operator, "CLEANUP_GOVERNANCE_SKIP_DRY_RUN", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                details);
        audits.append(operator, "CLEANUP_GOVERNANCE_EXECUTION_AUTHORIZED", "CLEANUP_GOVERNANCE", Long.toString(id),
                "SUCCESS", GovernanceAudit.details(next));
        long expectedVersion = next.version();
        runner.submitAfterCommit("CLEANUP_GOVERNANCE",
                () -> execute(id, expectedVersion, TtlGovernanceStatus.RUNNING, false));
        audits.append(operator, "CLEANUP_GOVERNANCE_START", "CLEANUP_GOVERNANCE", Long.toString(id), "SUCCESS",
                GovernanceAudit.details(next));
        return next;
    }
    /**
     * Replaces the async-job executing check: a run still heartbeating means the previous execution
     * thread has not finished exiting after a pause, so a resume must not start alongside it.
     */
    private void previousRunExiting(long id) {
        tasks.findRecentlyActiveRun(id, 10).ifPresent(run -> {
            throw new BusinessException("REQUEST_IN_PROGRESS", "上一个执行批次仍在退出，请稍后重试");
        });
    }
    /** One cleanup execution per cluster: a dry run or apply scans the whole cluster. */
    private void clusterIdle(long clusterId) {
        tasks.findActiveTaskForCluster(clusterId).ifPresent(active -> {
            throw new BusinessException("CLEANUP_CLUSTER_BUSY",
                    "cleanup task " + active.taskNo() + " is already running on this cluster");
        });
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

    /** Local-job entry point; fails the run and task closed on any error instead of requeueing. */
    public void execute(long id, long expectedVersion, TtlGovernanceStatus phase, boolean resume) {
        try {
            executeScan(id, expectedVersion, phase, resume);
        } catch (RuntimeException error) {
            String code = error.getMessage();
            if (code == null || !(code.matches("CLEANUP_[A-Z_]{1,40}") || code.matches("SCAN_[A-Z_]{1,40}")))
                code = "CLEANUP_FAILED";
            CleanupGovernanceTask task = get(id);
            if (accepts(expectedVersion, phase, task)) {
                final String failure = code;
                final CleanupGovernanceTask failing = task;
                inTransaction(() -> {
                    tasks.latestRun(id).ifPresent(run -> {
                        tasks.failRun(run.id(), Instant.now(), failure);
                        tasks.finalizeCheckpoints(run.id(), TtlGovernanceStatus.FAILED);
                    });
                    failTask(failing);
                });
                alert(id, code);
            }
            log.warn("cleanup governance task {} failed: {}", id, code);
            throw new IllegalStateException(code);
        }
    }
    /**
     * Apply checkpoints use the readable master@endpoint#nodeId form (preflight uses replica@...#...), so the
     * drawer can label scan nodes; scan routing keeps keying on the bare node id via shard.id().
     */
    private static String applyCheckpointId(ReadOnlyScanPort.Shard shard) {
        return shard.endpoint().isEmpty() ? shard.id() : "master@" + shard.endpoint() + "#" + shard.id();
    }
    private static boolean accepts(long expectedVersion, TtlGovernanceStatus phase, CleanupGovernanceTask task) {
        return task.version() == expectedVersion && task.status() == phase;
    }
    /**
     * Silent CAS: recovery and failure handling must not lose their error to a concurrent
     * pause/cancel — whichever transition lands first stands.
     */
    private void failTask(CleanupGovernanceTask task) {
        tasks.updateTask(new CleanupGovernanceTask(task.id(), task.taskNo(), task.clusterId(), task.databaseNo(),
                task.includePattern(), task.impactLimit(), task.scanRatePerSecond(), TtlGovernanceStatus.FAILED,
                task.approvalStatus(), task.approvalNote(), task.version(), task.createdAt(), Instant.now()),
                task.version());
    }
    private void executeScan(long id, long expectedVersion, TtlGovernanceStatus phase, boolean resume) {
        boolean apply = phase == TtlGovernanceStatus.RUNNING;
        CleanupGovernanceTask task = get(id);
        if (!accepts(expectedVersion, phase, task))
            return;
        var topology = apply ? null : reads.topology(task.clusterId(), task.databaseNo());
        var targets = apply
                ? redis.scanShards(task.clusterId(), task.databaseNo()).stream()
                        .map(s -> new ReadOnlyScanPort.Shard(s.id(), s.endpoint())).toList()
                : topology.shards();
        var existing = tasks.latestRun(id).orElse(null);
        // single assignment keeps run lambda-capturable for the parallel preflight workers
        var run = existing == null || !resume || existing.status() != phase
                ? tasks.saveRun(new CleanupGovernanceRun(null, id, "CRUN-" + id + "-" + System.currentTimeMillis(),
                        phase,
                        apply
                                ? redis.countKeys(task.clusterId(), task.databaseNo())
                                : reads.count(task.clusterId(), task.databaseNo(), topology),
                        0, 0, 0, 0, 0, Instant.now(), null, null))
                : existing;
        var ctx = new ScanContext(run);
        if (!apply) {
            Set<String> expected = new HashSet<>();
            for (var shard : targets)
                expected.add(topology.checkpointId(shard));
            var saved = tasks.checkpoints(run.id());
            if (saved.isEmpty() && ctx.scanned() == 0) {
                long seedRunId = run.id();
                inTransaction(() -> {
                    for (String target : expected)
                        tasks.saveCheckpoint(new CleanupGovernanceCheckpoint(seedRunId, target, "0", 0,
                                TtlGovernanceStatus.RUNNING, Instant.now()));
                });
            } else if (!expected
                    .equals(new HashSet<>(saved.stream().map(CleanupGovernanceCheckpoint::shardId).toList())))
                throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        }
        var matcher = matcherFor(task.includePattern());
        if (apply) {
            // writes stay serial: one master-scanning loop keeps the blast radius and audit trail simple
            for (var shard : targets)
                processShard(task, expectedVersion, phase, apply, topology, shard, applyCheckpointId(shard), run,
                        matcher, ctx);
        } else {
            // read-only preflight fans shards out on a bounded pool; each worker pins one replica connection
            var pool = Executors.newFixedThreadPool(Math.min(targets.size(), PREFLIGHT_PARALLELISM),
                    worker -> Thread.ofPlatform().name("cleanup-preflight-" + id).daemon().unstarted(worker));
            try {
                var failure = new AtomicReference<RuntimeException>();
                var futures = new ArrayList<Future<?>>(targets.size());
                for (var shard : targets) {
                    String shardId = topology.checkpointId(shard);
                    futures.add(pool.submit(() -> {
                        try {
                            processShard(task, expectedVersion, phase, false, topology, shard, shardId, run,
                                    matcher, ctx);
                        } catch (RuntimeException error) {
                            ctx.aborted = true;
                            failure.compareAndSet(null, error);
                        }
                    }));
                }
                for (var future : futures)
                    future.get();
                if (failure.get() != null)
                    throw failure.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GOVERNANCE_INTERRUPTED", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("GOVERNANCE_FAILED", e.getCause());
            } finally {
                pool.shutdownNow();
            }
        }
        var current = get(id);
        if (!accepts(expectedVersion, phase, current))
            return;
        if (!apply && !topology.fingerprint()
                .equals(reads.topology(current.clusterId(), current.databaseNo()).fingerprint()))
            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        var completed = ctx.snapshot(run, TtlGovernanceStatus.COMPLETED, Instant.now());
        inTransaction(() -> {
            transition(id, expectedVersion,
                    apply ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.AWAITING_APPROVAL,
                    apply ? TtlApprovalStatus.APPROVED : TtlApprovalStatus.PENDING, get(id).approvalNote());
            tasks.saveRun(completed);
            tasks.finalizeCheckpoints(run.id(), TtlGovernanceStatus.COMPLETED);
        });
    }
    /** Fails runs whose heartbeat went stale and tasks whose queued execution was lost; nothing is re-executed. */
    public void recoverInterrupted() {
        for (CleanupGovernanceRun run : tasks.findStaleRuns((int) STALE_RUN.toMinutes()))
            if (tasks.failRun(run.id(), Instant.now(), "GOVERNANCE_EXECUTOR_LOST")) {
                tasks.finalizeCheckpoints(run.id(), TtlGovernanceStatus.FAILED);
                CleanupGovernanceTask task = get(run.taskId());
                if (task.status() == TtlGovernanceStatus.DRY_RUN || task.status() == TtlGovernanceStatus.RUNNING)
                    failTask(task);
                log.warn("recovered stale cleanup governance run {} of task {}", run.id(), run.taskId());
            }
        // updated_at is written by the DB clock, so the staleness cut is computed DB-side.
        for (CleanupGovernanceTask task : tasks.findStaleQueuedTasks((int) STALE_QUEUED.toMinutes()))
            if (tasks.findRecentlyActiveRun(task.id(), (int) STALE_RUN.toMinutes()).isEmpty()) {
                failTask(task);
                log.warn("recovered stale queued cleanup governance task {}", task.id());
            }
    }
    private void alert(long id, String code) {
        try {
            alerts.trigger("CLEANUP_GOVERNANCE_FAILED", "CLEANUP_GOVERNANCE", Long.toString(id), 1d,
                    "Cleanup governance task failed closed: " + code);
        } catch (RuntimeException ignored) {
            // Alert delivery must not mask task failure handling.
        }
    }
    /**
     * Sleeps the reserved budget out in small steps so pause/cancel take effect quickly; heartbeats
     * throughout so a slow low-rate scan never looks executor-lost to the recovery sweep.
     */
    private void holdFor(long nanos, long id, long expectedVersion, TtlGovernanceStatus phase, long runId) {
        if (nanos <= 0)
            return;
        try {
            long deadline = System.nanoTime() + nanos;
            long touchedAt = 0L;
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0)
                    return;
                if (!accepts(expectedVersion, phase, get(id)))
                    return;
                if (System.currentTimeMillis() - touchedAt >= 5_000L) {
                    tasks.touchRun(runId);
                    touchedAt = System.currentTimeMillis();
                }
                Thread.sleep(Math.min(200, Math.max(1, remainingNanos / 1_000_000)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GOVERNANCE_INTERRUPTED", e);
        }
    }
    /**
     * Scans one shard to completion (or until paused/aborted); shared state lives in ctx so serial apply
     * and parallel preflight run the exact same page loop.
     */
    private void processShard(CleanupGovernanceTask initial, long expectedVersion, TtlGovernanceStatus phase,
            boolean apply, ReadOnlyScanPort.Topology topology, ReadOnlyScanPort.Shard shard, String shardId,
            CleanupGovernanceRun run, Predicate<String> matcher, ScanContext ctx) {
        var checkpoint = tasks.checkpoint(run.id(), shardId).orElse(null);
        if (checkpoint != null && checkpoint.status() == TtlGovernanceStatus.COMPLETED)
            return;
        String cursor = checkpoint == null ? "0" : checkpoint.cursor();
        long persistedAt = 0L;
        // one pinned connection per shard carries the whole dry-run scan; apply reads route per key instead
        try (ReadOnlyScanPort.Session session = apply
                ? null
                : reads.open(initial.clusterId(), initial.databaseNo(), shard)) {
            CleanupGovernanceTask task = initial;
            do {
                if (ctx.aborted)
                    return;
                task = get(task.id());
                if (!accepts(expectedVersion, phase, task))
                    return;
                // full topology probing costs several connections per call; recheck is throttled because
                // resume revalidates the checkpoint set and completion still verifies the fingerprint
                if (!apply && ctx.topologyCheckDue() && !topology.fingerprint()
                        .equals(reads.topology(task.clusterId(), task.databaseNo()).fingerprint()))
                    throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
                ReadOnlyScanPort.Page page;
                if (apply) {
                    var primaryPage = redis.scan(task.clusterId(), task.databaseNo(), shard.id(), cursor, PAGE);
                    page = new ReadOnlyScanPort.Page(primaryPage.nextCursor(),
                            primaryPage.keys().stream().map(RedisValidationPort.ValidationKey::bytes).toList());
                } else
                    page = session.scan(cursor, PAGE);
                cursor = page.cursor();
                List<byte[]> matched = new ArrayList<>();
                for (var key : page.keys()) {
                    if (key == null)
                        continue;
                    if (!matcher.test(new String(key, StandardCharsets.UTF_8)))
                        continue;
                    if (!ctx.claimScanned(task.impactLimit()))
                        break;
                    matched.add(key);
                }
                List<Long> ttls = List.of();
                if (!apply && !matched.isEmpty())
                    ttls = session.ttlBatch(matched);
                if (apply && !matched.isEmpty()) {
                    // one pipelined UNLINK round per page — the per-key form resolved the cluster profile
                    // (a DB roundtrip) for every single key; a vanished key reads as false
                    for (boolean deleted : redis.unlinkIfPresentBatch(task.clusterId(), task.databaseNo(),
                            matched)) {
                        if (deleted) {
                            ctx.candidates.incrementAndGet();
                            ctx.deleted.incrementAndGet();
                        } else
                            ctx.skipped.incrementAndGet();
                    }
                } else {
                    for (int i = 0; i < matched.size(); i++) {
                        if (ttls.get(i) == -2L) {
                            ctx.skipped.incrementAndGet();
                            continue;
                        }
                        ctx.candidates.incrementAndGet();
                    }
                }
                // page writes used to commit per page; 2s checkpoints cut DB roundtrips at the cost of
                // re-scanning at most one page per shard after a crash (scan/cursor are idempotent)
                boolean shardDone = "0".equals(cursor);
                long now = System.currentTimeMillis();
                if (shardDone || now - persistedAt >= PERSIST_INTERVAL.toMillis()) {
                    var savedCheckpoint = new CleanupGovernanceCheckpoint(run.id(), shardId, cursor, ctx.scanned(),
                            shardDone ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.RUNNING, Instant.now());
                    var progress = ctx.snapshot(run, phase, null);
                    inTransaction(() -> {
                        tasks.saveCheckpoint(savedCheckpoint);
                        tasks.saveRun(progress);
                    });
                    persistedAt = now;
                }
                holdFor(ctx.pacer.reserve(page.keys().size(), task.scanRatePerSecond()), initial.id(),
                        expectedVersion, phase, run.id());
            } while (!"0".equals(cursor) && !ctx.aborted && ctx.scanned() < task.impactLimit());
        }
    }
    /**
     * Cluster-wide token bucket shared by all shard workers: freeAtNanos slides forward by keys/rate, so
     * parallel preflight still honors one global cap and elapsed work time is credited automatically
     * (the rate is a ceiling, not a floor).
     */
    private static final class Pacer {
        private long freeAtNanos;
        synchronized long reserve(int count, int rate) {
            if (rate <= 0)
                return 0;
            long now = System.nanoTime();
            freeAtNanos = Math.max(freeAtNanos, now) + count * 1_000_000_000L / rate;
            return Math.max(0, freeAtNanos - now);
        }
    }
    /**
     * Counters and throttle state shared across parallel preflight workers; seeded from the resumed run so
     * a restart continues the exact global scanned/cursor pair.
     */
    private static final class ScanContext {
        private final AtomicLong scanned;
        private final AtomicLong candidates = new AtomicLong();
        private final AtomicLong deleted = new AtomicLong();
        private final AtomicLong skipped = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        final Pacer pacer = new Pacer();
        volatile boolean aborted;
        private long topologyCheckedAt;
        ScanContext(CleanupGovernanceRun run) {
            scanned = new AtomicLong(run.scannedKeys());
            candidates.set(run.candidateKeys());
            deleted.set(run.deletedKeys());
            skipped.set(run.skippedKeys());
            failed.set(run.failedKeys());
        }
        long scanned() {
            return scanned.get();
        }
        /** Claims one matched key against the global impact limit (check-and-increment, exact under races). */
        boolean claimScanned(long cap) {
            while (true) {
                long current = scanned.get();
                if (current >= cap)
                    return false;
                if (scanned.compareAndSet(current, current + 1))
                    return true;
            }
        }
        synchronized boolean topologyCheckDue() {
            if (System.currentTimeMillis() - topologyCheckedAt < 30_000L)
                return false;
            topologyCheckedAt = System.currentTimeMillis();
            return true;
        }
        synchronized CleanupGovernanceRun snapshot(CleanupGovernanceRun run, TtlGovernanceStatus phase,
                Instant completedAt) {
            return new CleanupGovernanceRun(run.id(), run.taskId(), run.runNo(), phase, run.plannedKeys(),
                    scanned.get(), candidates.get(), deleted.get(), skipped.get(), failed.get(), run.startedAt(),
                    completedAt, null);
        }
    }
    private static java.util.function.Predicate<String> matcherFor(String pattern) {
        if ("*".equals(pattern))
            return name -> true;
        var compiled = Pattern.compile(pattern.replace(".", "\\.").replace("*", ".*"));
        return name -> compiled.matcher(name).matches();
    }
}
