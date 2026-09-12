package io.github.redisops.application.risk;
import io.github.redisops.common.*;
import io.github.redisops.application.alert.AlertService;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.job.JobRepository;
import io.github.redisops.domain.risk.*;
import io.github.redisops.domain.validation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class RiskScanService {
    private final RiskScanRepository scans;
    private final ClusterRepository clusters;
    private final JobRepository jobs;
    private final RedisValidationPort redis;
    private final AlertService alerts;
    public RiskScanService(RiskScanRepository s, ClusterRepository c, JobRepository j, RedisValidationPort r,
            AlertService a) {
        scans = s;
        clusters = c;
        jobs = j;
        redis = r;
        alerts = a;
    }
    @Transactional
    public RiskScanTask create(long clusterId, Integer db, String pattern, Long threshold, Integer rate, Integer max) {
        RedisCluster c = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        if (c.status() != ClusterStatus.ACTIVE)
            throw new BusinessException("SCAN_CLUSTER_UNAVAILABLE", "cluster must be active");
        int database = c.mode() == ClusterMode.CLUSTER ? 0 : db == null ? 0 : db;
        if (c.mode() == ClusterMode.CLUSTER && db != null && db != 0)
            throw new BusinessException("SCAN_INVALID_DB", "cluster database must be 0");
        return scans.saveTask(new RiskScanTask(null, "SCAN-" + UUID.randomUUID().toString().substring(0, 12), clusterId,
                database, pattern == null || pattern.isBlank() ? "*" : pattern, true, true,
                threshold == null ? 64L * 1024 * 1024 : threshold, rate == null ? 1000 : rate, max == null ? 1000 : max,
                RiskScanStatus.CREATED, 0, Instant.now(), Instant.now()));
    }
    public RiskScanTask get(long id) {
        return scans.findTask(id).orElseThrow(() -> BusinessException.notFound("scanTask", id));
    }
    public List<RiskScanTask> list() {
        return scans.findTasks();
    }
    public Optional<RiskScanRun> latest(long id) {
        return scans.latestRun(id);
    }
    public List<RiskScanCheckpoint> checkpoints(long taskId) {
        return latest(taskId).map(run -> scans.checkpoints(run.id())).orElse(List.of());
    }
    public RiskSummary summary(long taskId) {
        return latest(taskId).map(run -> new RiskSummary(run.scannedKeys(),
                scans.findingCountByType(run.id(), "NO_TTL"), scans.findingCountByType(run.id(), "LARGE_KEY")))
                .orElse(new RiskSummary(0, 0, 0));
    }
    public record RiskSummary(long scannedKeys, long noTtlCount, long largeKeyCount) {
        public double noTtlRatio() {
            return scannedKeys == 0 ? 0 : (double) noTtlCount / scannedKeys;
        }
    }
    public PageResult<RiskFinding> findings(long id, int p, int s, String riskType) {
        return scans.latestRun(id).map(x -> scans.findings(x.id(), p, s, riskType))
                .orElse(new PageResult<>(List.of(), 0, p, s));
    }
    @Transactional
    public RiskScanTask start(long id, long version, String key) {
        RiskScanTask t = get(id);
        if (t.status() == RiskScanStatus.RUNNING)
            throw new BusinessException("SCAN_RUNNING", "scan is already running");
        RiskScanTask changed = new RiskScanTask(t.id(), t.taskNo(), t.clusterId(), t.databaseNo(), t.includePattern(),
                t.checkLargeKey(), t.checkNoTtl(),
                t.largeKeyThresholdBytes(), t.scanRatePerSecond(), t.maxFindings(), RiskScanStatus.RUNNING, t.version(),
                t.createdAt(), Instant.now());
        if (!scans.updateTask(changed, version))
            throw new BusinessException("VERSION_CONFLICT", "scan task version changed");
        jobs.enqueue("RISK_SCAN", id, "{\"scanTaskId\":" + id + "}", key);
        return get(id);
    }
    @Transactional
    public RiskScanTask cancel(long id, long version) {
        RiskScanTask task = get(id);
        if (task.status() != RiskScanStatus.RUNNING)
            throw new BusinessException("SCAN_NOT_RUNNING", "only a running scan can be cancelled");
        RiskScanTask changed = new RiskScanTask(task.id(), task.taskNo(), task.clusterId(), task.databaseNo(),
                task.includePattern(), task.checkLargeKey(), task.checkNoTtl(), task.largeKeyThresholdBytes(),
                task.scanRatePerSecond(), task.maxFindings(),
                RiskScanStatus.CANCELLED, task.version(), task.createdAt(), Instant.now());
        if (!scans.updateTask(changed, version))
            throw new BusinessException("VERSION_CONFLICT", "scan task version changed");
        return get(id);
    }
    @Transactional
    public void execute(long id) {
        RiskScanTask t = get(id);
        if (t.status() == RiskScanStatus.CANCELLED)
            return;
        RiskScanRun run = scans.latestRun(id).filter(value -> value.status() == RiskScanStatus.RUNNING)
                .orElseGet(() -> scans
                        .saveRun(new RiskScanRun(null, id, "SRUN-" + UUID.randomUUID().toString().substring(0, 12),
                                RiskScanStatus.RUNNING, redis.countKeys(t.clusterId(), t.databaseNo()), 0, 0,
                                Instant.now(), null, null)));
        long scanned = run.scannedKeys();
        long findings = run.findingCount();
        ValidationTask probe = new ValidationTask(null, "probe", null, t.clusterId(), t.clusterId(), t.databaseNo(),
                t.databaseNo(), ValidationStrictness.REPORT, "[]", "[]", "risk", ValidationSamplingMode.COUNT, 1, null,
                0, t.largeKeyThresholdBytes(), 1, 1024, 1, ValidationTaskStatus.CREATED, null, 0, Instant.now(),
                Instant.now());
        for (var shard : redis.scanShards(t.clusterId(), t.databaseNo())) {
            RiskScanCheckpoint checkpoint = scans.checkpoint(run.id(), shard.id())
                    .orElse(new RiskScanCheckpoint(run.id(), shard.id(), "0", 0, RiskScanStatus.RUNNING,
                            Instant.now()));
            String cursor = checkpoint.cursor();
            do {
                long pageStartedAt = System.nanoTime();
                if (get(id).status() == RiskScanStatus.CANCELLED) {
                    scans.saveCheckpoint(new RiskScanCheckpoint(run.id(), shard.id(), cursor, checkpoint.scannedKeys(),
                            RiskScanStatus.CANCELLED, Instant.now()));
                    scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.CANCELLED,
                            run.plannedKeys(), scanned,
                            findings, run.startedAt(), Instant.now(), null));
                    return;
                }
                var page = redis.scan(t.clusterId(), t.databaseNo(), shard.id(), cursor, 200);
                cursor = page.nextCursor();
                List<RiskFinding> batch = new ArrayList<>();
                for (var key : page.keys()) {
                    if (findings + batch.size() >= t.maxFindings())
                        break;
                    if (!glob(t.includePattern(), new String(key.bytes(), StandardCharsets.UTF_8)))
                        continue;
                    scanned++;
                    var value = redis.inspect(t.clusterId(), t.databaseNo(), key.bytes(), probe);
                    if (value.isEmpty())
                        continue;
                    var inspected = value.get();
                    String keyName = new String(key.bytes(), StandardCharsets.UTF_8);
                    String keyHash = hash(key.bytes());
                    if (t.checkLargeKey() && inspected.size() >= t.largeKeyThresholdBytes()
                            && findings + batch.size() < t.maxFindings())
                        batch.add(new RiskFinding(null, run.id(), "LARGE_KEY",
                                level(inspected.size(), t.largeKeyThresholdBytes()), keyName, keyHash, inspected.type(),
                                inspected.size(), null, inspected.ttlSeconds(), shard.id(), Instant.now()));
                    if (t.checkNoTtl() && inspected.ttlSeconds() == -1 && findings + batch.size() < t.maxFindings())
                        batch.add(new RiskFinding(null, run.id(), "NO_TTL", RiskLevel.MEDIUM, keyName, keyHash,
                                inspected.type(),
                                inspected.size(), null, -1L, shard.id(), Instant.now()));
                }
                scans.saveFindings(batch);
                findings += batch.size();
                checkpoint = new RiskScanCheckpoint(run.id(), shard.id(), cursor,
                        checkpoint.scannedKeys() + page.keys().size(),
                        "0".equals(cursor) ? RiskScanStatus.COMPLETED : RiskScanStatus.RUNNING, Instant.now());
                scans.saveCheckpoint(checkpoint);
                scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.RUNNING, run.plannedKeys(),
                        scanned, findings,
                        run.startedAt(), null, null));
                rateLimit(page.keys().size(), t.scanRatePerSecond(), pageStartedAt);
            } while (!"0".equals(cursor) && findings < t.maxFindings());
        }
        scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.COMPLETED, run.plannedKeys(), scanned,
                findings,
                run.startedAt(), Instant.now(), null));
        if (findings > 0)
            alerts.trigger("LARGE_KEY_FOUND", "REDIS_CLUSTER", Long.toString(t.clusterId()), (double) findings,
                    "Read-only risk scan found large keys");
        RiskScanTask latest = get(id);
        scans.updateTask(new RiskScanTask(latest.id(), latest.taskNo(), latest.clusterId(), latest.databaseNo(),
                latest.includePattern(), latest.checkLargeKey(), latest.checkNoTtl(), latest.largeKeyThresholdBytes(),
                latest.scanRatePerSecond(),
                latest.maxFindings(), RiskScanStatus.COMPLETED, latest.version(), latest.createdAt(), Instant.now()),
                latest.version());
    }
    private static boolean glob(String p, String k) {
        return "*".equals(p) || k.matches(p.replace(".", "\\.").replace("*", ".*"));
    }
    private static String hash(byte[] v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private static RiskLevel level(long n, long t) {
        return n >= t * 10
                ? RiskLevel.CRITICAL
                : n >= t * 4 ? RiskLevel.HIGH : n >= t * 2 ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }
    private static void rateLimit(int count, int perSecond, long startedAt) {
        long requiredNanos = count * 1_000_000_000L / Math.max(1, perSecond);
        long remainingMillis = (requiredNanos - (System.nanoTime() - startedAt)) / 1_000_000L;
        if (remainingMillis <= 0)
            return;
        try {
            Thread.sleep(remainingMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("risk scan interrupted", interrupted);
        }
    }
}
