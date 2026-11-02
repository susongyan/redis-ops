package io.github.susongyan.redisops.platform.application.risk;
import io.github.susongyan.redisops.platform.common.*;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.risk.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class RiskScanService {
    private static final Logger log = LoggerFactory.getLogger(RiskScanService.class);
    private static final Duration STALE_RUN = Duration.ofMinutes(10);
    private static final Duration STALE_QUEUED = Duration.ofMinutes(30);
    private static final int PAGE = 2000;
    /** bounded like DistributionCounter capacity; beyond this every new pattern folds into GROUP_LIMIT */
    private static final int MAX_GROUPS = 1000;
    static final String GROUP_LIMIT = "GROUP_LIMIT";
    static final String GROUP_TOO_LONG = "GROUP_TOO_LONG";
    static final String BINARY_KEY = "BINARY_KEY";
    static final String UNCLASSIFIED = "(unclassified)";
    private final RiskScanRepository scans;
    private final ClusterRepository clusters;
    private final LocalJobRunner runner;
    private final ReadOnlyScanPort redis;
    private final AlertService alerts;
    private final TransactionTemplate pages;
    public RiskScanService(RiskScanRepository s, ClusterRepository c, LocalJobRunner j, ReadOnlyScanPort r,
            AlertService a) {
        this(s, c, j, r, a, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RiskScanService(RiskScanRepository s, ClusterRepository c, LocalJobRunner j, ReadOnlyScanPort r,
            AlertService a, org.springframework.transaction.PlatformTransactionManager manager) {
        scans = s;
        clusters = c;
        runner = j;
        redis = r;
        alerts = a;
        pages = manager == null ? null : new TransactionTemplate(manager);
    }
    /** Progress writes commit per page; the scan loop itself must not run inside one long transaction. */
    private void inTransaction(Runnable work) {
        if (pages == null) {
            work.run();
            return;
        }
        pages.executeWithoutResult(status -> work.run());
    }
    @Transactional
    public RiskScanTask create(long clusterId, Integer db, String pattern, Long threshold, Integer rate, Integer max,
            String groupDelimiter, Integer groupSegments) {
        RedisCluster c = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        if (c.status() != ClusterStatus.ACTIVE)
            throw new BusinessException("SCAN_CLUSTER_UNAVAILABLE", "cluster must be active");
        int database = c.mode() == ClusterMode.CLUSTER ? 0 : db == null ? 0 : db;
        if (c.mode() == ClusterMode.CLUSTER && db != null && db != 0)
            throw new BusinessException("SCAN_INVALID_DB", "cluster database must be 0");
        String delimiter = groupDelimiter == null || groupDelimiter.isBlank() ? ":" : groupDelimiter;
        if (delimiter.getBytes(StandardCharsets.UTF_8).length > 8)
            throw new BusinessException("SCAN_INVALID_GROUP_DELIMITER", "delimiter must be 1-8 bytes");
        int segments = groupSegments == null ? 2 : groupSegments;
        if (segments < 1 || segments > 8)
            throw new BusinessException("SCAN_INVALID_GROUP_SEGMENTS", "segments must be 1-8");
        return scans.saveTask(new RiskScanTask(null, "SCAN-" + UUID.randomUUID().toString().substring(0, 12), clusterId,
                database, pattern == null || pattern.isBlank() ? "*" : pattern, true, true,
                threshold == null ? 64L * 1024 * 1024 : threshold, rate == null ? 1000 : rate, max == null ? 1000 : max,
                delimiter, segments, RiskScanStatus.CREATED, 0, Instant.now(), Instant.now()));
    }
    public RiskScanTask get(long id) {
        return scans.findTask(id).orElseThrow(() -> BusinessException.notFound("scanTask", id));
    }
    public List<RiskScanTask> list() {
        return scans.findTasks();
    }
    public Optional<RiskScanRun> latest(long id) {
        get(id);
        return scans.latestRun(id);
    }
    public List<RiskScanCheckpoint> checkpoints(long taskId) {
        return latest(taskId).map(run -> scans.checkpoints(run.id())).orElse(List.of());
    }
    public RiskSummary summary(long taskId) {
        return latest(taskId).map(run -> new RiskSummary(run.scannedKeys(),
                run.noTtlTotal() == null ? scans.findingCountByType(run.id(), "NO_TTL") : run.noTtlTotal(),
                run.largeKeyTotal() == null ? scans.findingCountByType(run.id(), "LARGE_KEY") : run.largeKeyTotal()))
                .orElse(new RiskSummary(0, 0, 0));
    }
    public record RiskSummary(long scannedKeys, long noTtlCount, long largeKeyCount, double noTtlRatio) {
        public RiskSummary(long scannedKeys, long noTtlCount, long largeKeyCount) {
            this(scannedKeys, noTtlCount, largeKeyCount,
                    scannedKeys == 0 ? 0 : (double) noTtlCount / scannedKeys);
        }
    }
    public PageResult<RiskFinding> findings(long id, int p, int s, String riskType) {
        get(id);
        return scans.latestRun(id).map(x -> scans.findings(x.id(), p, s, riskType))
                .orElse(new PageResult<>(List.of(), 0, p, s));
    }
    /** Grouped NO_TTL view; empty for legacy runs that predate grouping. */
    public PageResult<RiskFindingGroup> noTtlGroups(long id, int p, int s) {
        get(id);
        return scans.latestRun(id).filter(run -> run.noTtlTotal() != null)
                .map(run -> scans.groups(run.id(), p, s)).orElse(new PageResult<>(List.of(), 0, p, s));
    }
    @Transactional
    public RiskScanTask start(long id, long version, String key) {
        RiskScanTask t = get(id);
        if (t.status() == RiskScanStatus.RUNNING)
            throw new BusinessException("SCAN_RUNNING", "scan is already running");
        scans.findActiveTaskForCluster(t.clusterId()).ifPresent(active -> {
            throw new BusinessException("SCAN_CLUSTER_BUSY",
                    "scan task " + active.taskNo() + " is already running on this cluster");
        });
        runner.checkCapacity("RISK_SCAN");
        if (!scans.updateTask(RiskScanTask.withStatus(t, RiskScanStatus.RUNNING, Instant.now()), version))
            throw new BusinessException("VERSION_CONFLICT", "scan task version changed");
        runner.submitAfterCommit("RISK_SCAN", () -> execute(id));
        return get(id);
    }
    @Transactional
    public RiskScanTask cancel(long id, long version) {
        RiskScanTask task = get(id);
        if (task.status() != RiskScanStatus.RUNNING)
            throw new BusinessException("SCAN_NOT_RUNNING", "only a running scan can be cancelled");
        if (!scans.updateTask(RiskScanTask.withStatus(task, RiskScanStatus.CANCELLED, Instant.now()), version))
            throw new BusinessException("VERSION_CONFLICT", "scan task version changed");
        return get(id);
    }
    public void execute(long id) {
        try {
            executeScan(id);
        } catch (RuntimeException error) {
            String code = error.getMessage();
            if (code == null || !code.matches("SCAN_[A-Z_]{1,40}"))
                code = "SCAN_FAILED";
            var task = get(id);
            if (task.status() == RiskScanStatus.RUNNING) {
                final String failure = code;
                inTransaction(() -> {
                    scans.latestRun(id).ifPresent(run -> scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(),
                            RiskScanStatus.FAILED, run.plannedKeys(), run.scannedKeys(), run.findingCount(),
                            run.noTtlTotal(), run.largeKeyTotal(), run.startedAt(), Instant.now(), failure)));
                    scans.updateTask(RiskScanTask.withStatus(task, RiskScanStatus.FAILED, Instant.now()),
                            task.version());
                });
            }
            log.warn("risk scan task {} failed: {}", id, code);
            throw new IllegalStateException(code);
        }
    }
    /** Fails runs whose heartbeat went stale and tasks whose queued execution was lost. */
    public void recoverInterrupted() {
        Instant staleRun = Instant.now().minus(STALE_RUN);
        for (RiskScanRun run : scans.findStaleRuns(staleRun))
            if (scans.failRun(run.id())) {
                RiskScanTask task = get(run.taskId());
                if (task.status() == RiskScanStatus.RUNNING)
                    failTask(task);
                log.warn("recovered stale risk scan run {} of task {}", run.id(), run.taskId());
            }
        // updated_at is written by the DB clock, so the staleness cut is computed DB-side.
        for (RiskScanTask task : scans.findStaleQueuedTasks((int) STALE_QUEUED.toMinutes()))
            if (scans.latestRun(task.id()).filter(r -> r.status() == RiskScanStatus.RUNNING).isEmpty()) {
                failTask(task);
                log.warn("recovered stale queued risk scan task {}", task.id());
            }
    }
    private void failTask(RiskScanTask task) {
        scans.updateTask(RiskScanTask.withStatus(task, RiskScanStatus.FAILED, Instant.now()), task.version());
    }
    private void executeScan(long id) {
        RiskScanTask t = get(id);
        if (t.status() != RiskScanStatus.RUNNING)
            return;
        var topology = redis.topology(t.clusterId(), t.databaseNo());
        RiskScanRun run = scans.latestRun(id).filter(value -> value.status() == RiskScanStatus.RUNNING)
                .orElseGet(() -> scans
                        .saveRun(new RiskScanRun(null, id, "SRUN-" + UUID.randomUUID().toString().substring(0, 12),
                                RiskScanStatus.RUNNING, redis.count(t.clusterId(), t.databaseNo(), topology), 0, 0,
                                0L, 0L, Instant.now(), null, null)));
        long scanned = run.scannedKeys();
        long noTtlTotal = run.noTtlTotal() == null ? 0 : run.noTtlTotal();
        long largeKeyTotal = run.largeKeyTotal() == null ? 0 : run.largeKeyTotal();
        long persisted = run.findingCount();
        var savedTargets = scans.checkpoints(run.id());
        Set<String> expected = new HashSet<>();
        for (var shard : topology.shards())
            expected.add(topology.checkpointId(shard));
        if (savedTargets.isEmpty() && scanned == 0) {
            inTransaction(() -> {
                for (String target : expected)
                    scans.saveCheckpoint(new RiskScanCheckpoint(run.id(), target, "0", 0,
                            RiskScanStatus.RUNNING, Instant.now()));
            });
        } else if (!expected.equals(new HashSet<>(savedTargets.stream().map(RiskScanCheckpoint::shardId).toList())))
            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        Set<String> knownGroups = new HashSet<>(scans.groupTexts(run.id()));
        var matcher = matcherFor(t.includePattern());
        long topologyCheckedAt = 0L;
        for (var shard : topology.shards()) {
            String shardId = topology.checkpointId(shard);
            RiskScanCheckpoint checkpoint = scans.checkpoint(run.id(), shardId)
                    .orElse(new RiskScanCheckpoint(run.id(), shardId, "0", 0, RiskScanStatus.RUNNING,
                            Instant.now()));
            if (checkpoint.status() == RiskScanStatus.COMPLETED)
                continue;
            String cursor = checkpoint.cursor();
            try (ReadOnlyScanPort.Session session = redis.open(t.clusterId(), t.databaseNo(), shard)) {
                do {
                    long pageStartedAt = System.nanoTime();
                    if (get(id).status() == RiskScanStatus.CANCELLED) {
                        final String resumeCursor = cursor;
                        final long resumeScanned = checkpoint.scannedKeys();
                        final long cancelScanned = scanned;
                        final long cancelNoTtl = noTtlTotal;
                        final long cancelLargeKey = largeKeyTotal;
                        final long cancelPersisted = persisted;
                        inTransaction(() -> {
                            scans.saveCheckpoint(new RiskScanCheckpoint(run.id(), shardId, resumeCursor, resumeScanned,
                                    RiskScanStatus.CANCELLED, Instant.now()));
                            scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.CANCELLED,
                                    run.plannedKeys(), cancelScanned, cancelPersisted, cancelNoTtl, cancelLargeKey,
                                    run.startedAt(), Instant.now(), null));
                        });
                        return;
                    }
                    // full topology probing costs several connections per call; the recheck is throttled because
                    // resume revalidates the checkpoint set and completion still verifies the fingerprint
                    if (System.currentTimeMillis() - topologyCheckedAt >= 30_000L) {
                        if (!topology.fingerprint().equals(redis.topology(t.clusterId(), t.databaseNo()).fingerprint()))
                            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
                        topologyCheckedAt = System.currentTimeMillis();
                    }
                    var page = session.scan(cursor, PAGE);
                    cursor = page.cursor();
                    List<byte[]> matched = new ArrayList<>();
                    for (var key : page.keys()) {
                        if (key == null)
                            continue;
                        if (!matcher.test(new String(key, StandardCharsets.UTF_8)))
                            continue;
                        scanned++;
                        matched.add(key);
                    }
                    List<ReadOnlyScanPort.Metadata> inspectedPage = matched.isEmpty()
                            ? List.of()
                            : session.metadataBatch(matched);
                    List<RiskFinding> batch = new ArrayList<>();
                    Map<String, RiskFindingGroup> pageGroups = new LinkedHashMap<>();
                    for (int i = 0; i < matched.size(); i++) {
                        var inspected = inspectedPage.get(i);
                        if ("none".equals(inspected.type()))
                            continue;
                        byte[] key = matched.get(i);
                        String keyName = new String(key, StandardCharsets.UTF_8);
                        String keyHash = hash(key);
                        if (t.checkLargeKey() && inspected.size() >= t.largeKeyThresholdBytes()) {
                            largeKeyTotal++;
                            if (persisted < t.maxFindings()) {
                                persisted++;
                                batch.add(new RiskFinding(null, run.id(), "LARGE_KEY",
                                        level(inspected.size(), t.largeKeyThresholdBytes()), keyName, keyHash,
                                        inspected.type(), inspected.size(), null, inspected.ttlSeconds(), shardId,
                                        Instant.now()));
                            }
                        }
                        if (t.checkNoTtl() && inspected.ttlSeconds() == -1) {
                            noTtlTotal++;
                            String group = boundedGroup(groupOf(key, t.groupDelimiter(), t.groupSegments()),
                                    knownGroups);
                            long size = inspected.size();
                            pageGroups.merge(group,
                                    new RiskFindingGroup(run.id(), RiskFindingGroup.NO_TTL, group, 1,
                                            sampleKey(keyName), size),
                                    (a, b) -> new RiskFindingGroup(a.runId(), a.riskType(), a.groupText(),
                                            a.hitCount() + b.hitCount(), a.sampleKey(),
                                            Math.max(a.maxMemoryBytes(), b.maxMemoryBytes())));
                        }
                    }
                    RiskScanCheckpoint next = new RiskScanCheckpoint(run.id(), shardId, cursor,
                            checkpoint.scannedKeys() + page.keys().size(),
                            "0".equals(cursor) ? RiskScanStatus.COMPLETED : RiskScanStatus.RUNNING, Instant.now());
                    final long scannedNow = scanned;
                    final long noTtlNow = noTtlTotal;
                    final long largeKeyNow = largeKeyTotal;
                    final long persistedNow = persisted;
                    final List<RiskFinding> pageFindings = batch;
                    final List<RiskFindingGroup> groups = new ArrayList<>(pageGroups.values());
                    final RiskScanCheckpoint target = next;
                    inTransaction(() -> {
                        scans.saveFindings(pageFindings);
                        scans.saveGroups(groups);
                        scans.saveCheckpoint(target);
                        scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.RUNNING,
                                run.plannedKeys(), scannedNow, persistedNow, noTtlNow, largeKeyNow,
                                run.startedAt(), null, null));
                    });
                    checkpoint = next;
                    rateLimit(page.keys().size(), t.scanRatePerSecond(), pageStartedAt);
                } while (!"0".equals(cursor));
            }
        }
        if (!topology.fingerprint().equals(redis.topology(t.clusterId(), t.databaseNo()).fingerprint()))
            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        final long finalScanned = scanned;
        final long finalNoTtl = noTtlTotal;
        final long finalLargeKey = largeKeyTotal;
        final long finalPersisted = persisted;
        inTransaction(() -> {
            scans.saveRun(new RiskScanRun(run.id(), id, run.runNo(), RiskScanStatus.COMPLETED, run.plannedKeys(),
                    finalScanned, finalPersisted, finalNoTtl, finalLargeKey, run.startedAt(), Instant.now(), null));
            RiskScanTask latest = get(id);
            scans.updateTask(RiskScanTask.withStatus(latest, RiskScanStatus.COMPLETED, Instant.now()),
                    latest.version());
        });
        if (largeKeyTotal > 0)
            alerts.trigger("LARGE_KEY_FOUND", "REDIS_CLUSTER", Long.toString(t.clusterId()),
                    (double) largeKeyTotal, "Read-only risk scan found large keys");
    }
    /**
     * First N segments on the delimiter; volatile segments (numeric or long hex) collapse to * so
     * per-entity prefixes like user:10086 group together; unclassifiable keys land in system groups.
     */
    static String groupOf(byte[] key, String delimiter, int segments) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(key)).toString();
        } catch (CharacterCodingException e) {
            return BINARY_KEY;
        }
        String[] parts = text.split(java.util.regex.Pattern.quote(delimiter), -1);
        StringBuilder grouped = new StringBuilder();
        boolean anyLiteral = false;
        for (int i = 0; i < parts.length && i < segments; i++) {
            String part = parts[i];
            boolean volatileSegment = isVolatile(part);
            if (!volatileSegment)
                anyLiteral = true;
            if (grouped.length() > 0)
                grouped.append(delimiter);
            grouped.append(volatileSegment ? "*" : part);
        }
        if (!anyLiteral)
            return UNCLASSIFIED;
        String group = grouped.toString();
        return group.getBytes(StandardCharsets.UTF_8).length > 256 ? GROUP_TOO_LONG : group;
    }
    private static boolean isVolatile(String segment) {
        if (segment.isEmpty())
            return false;
        boolean digits = true;
        for (int i = 0; i < segment.length(); i++)
            if (!Character.isDigit(segment.charAt(i))) {
                digits = false;
                break;
            }
        if (digits)
            return true;
        if (segment.length() < 8)
            return false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F'))
                return false;
        }
        return true;
    }
    private static String boundedGroup(String group, Set<String> known) {
        if (known.contains(group))
            return group;
        if (known.size() >= MAX_GROUPS)
            return GROUP_LIMIT;
        known.add(group);
        return group;
    }
    private static String sampleKey(String keyName) {
        return keyName.length() <= 512 ? keyName : keyName.substring(0, 512);
    }
    private static java.util.function.Predicate<String> matcherFor(String pattern) {
        if ("*".equals(pattern))
            return name -> true;
        var compiled = java.util.regex.Pattern.compile(pattern.replace(".", "\\.").replace("*", ".*"));
        return name -> compiled.matcher(name).matches();
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
