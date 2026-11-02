package io.github.susongyan.redisops.platform.application.validation;

import io.github.susongyan.redisops.platform.common.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.validation.*;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationService {
    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private static final long MAX_THRESHOLD = 1024L * 1024 * 1024;
    /** A run whose heartbeat (or start, when no page finished yet) is older than this is dead. */
    private static final Duration STALE_RUN = Duration.ofMinutes(10);
    /** A task stuck in CHECKING this long lost its queued execution (the pool died before it ran). */
    private static final Duration STALE_QUEUED = Duration.ofMinutes(30);
    /** Progress, heartbeat and cancellation checks are published on this cadence once shards run in parallel. */
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);
    private final ValidationRepository validations;
    private final ClusterRepository clusters;
    private final LocalJobRunner runner;
    private final RedisValidationPort redis;
    private final int shardParallelism;

    public ValidationService(ValidationRepository validations, ClusterRepository clusters, LocalJobRunner runner,
            RedisValidationPort redis) {
        this(validations, clusters, runner, redis, 4);
    }

    @Autowired
    public ValidationService(ValidationRepository validations, ClusterRepository clusters, LocalJobRunner runner,
            RedisValidationPort redis,
            @Value("${platform.validation.shard-parallelism:4}") int shardParallelism) {
        this.validations = validations;
        this.clusters = clusters;
        this.runner = runner;
        this.redis = redis;
        this.shardParallelism = Math.max(1, shardParallelism);
    }

    @Transactional
    public ValidationTask create(Long syncTaskId, long sourceClusterId, long targetClusterId, Integer sourceDb,
            Integer targetDb, ValidationStrictness strictness, List<String> includePatterns,
            List<String> excludePatterns, ValidationSamplingMode samplingMode, Integer sampleLimit,
            Double samplePercentage, Long ttlToleranceSeconds, Long largeKeyThresholdBytes,
            Long maxDeepCompareBytes, Integer chunkBytes, Integer maxElementsPerKey) {
        if (sourceClusterId == targetClusterId)
            throw new BusinessException("VALIDATION_SAME_CLUSTER", "source and target clusters must differ");
        RedisCluster source = cluster(sourceClusterId);
        RedisCluster target = cluster(targetClusterId);
        if (source.status() != ClusterStatus.ACTIVE || target.status() != ClusterStatus.ACTIVE)
            throw new BusinessException("VALIDATION_CLUSTER_UNAVAILABLE", "source and target clusters must be active");
        int sourceDatabase = database(source, sourceDb, "sourceDb");
        int targetDatabase = database(target, targetDb, "targetDb");
        long large = bounded(largeKeyThresholdBytes, ValidationTask.DEFAULT_LARGE_KEY_THRESHOLD_BYTES, 1, MAX_THRESHOLD,
                "largeKeyThresholdBytes");
        long deep = bounded(maxDeepCompareBytes, ValidationTask.DEFAULT_MAX_DEEP_COMPARE_BYTES, 1, large,
                "maxDeepCompareBytes");
        int chunk = (int) bounded(chunkBytes == null ? null : chunkBytes.longValue(),
                ValidationTask.DEFAULT_CHUNK_BYTES,
                1024, 8L * 1024 * 1024, "chunkBytes");
        int elements = (int) bounded(maxElementsPerKey == null ? null : maxElementsPerKey.longValue(),
                ValidationTask.DEFAULT_MAX_ELEMENTS_PER_KEY, 1, 1_000_000, "maxElementsPerKey");
        ValidationSamplingMode mode = samplingMode == null ? ValidationSamplingMode.COUNT : samplingMode;
        int sample = mode == ValidationSamplingMode.COUNT
                ? (int) bounded(sampleLimit == null ? null : sampleLimit.longValue(),
                        ValidationTask.DEFAULT_SAMPLE_LIMIT, 1, 1_000_000, "sampleLimit")
                : 0;
        Double percentage = mode == ValidationSamplingMode.PERCENTAGE ? samplePercentage : null;
        if (mode == ValidationSamplingMode.PERCENTAGE && (percentage == null || percentage <= 0 || percentage > 100))
            throw new BusinessException("VALIDATION_INVALID_LIMIT", "samplePercentage must be within (0, 100]");
        long ttl = bounded(ttlToleranceSeconds, ValidationTask.DEFAULT_TTL_TOLERANCE_SECONDS, 0, 3600,
                "ttlToleranceSeconds");
        Instant now = Instant.now();
        return validations.saveTask(new ValidationTask(null, "VAL-" + UUID.randomUUID().toString().substring(0, 12),
                syncTaskId, sourceClusterId, targetClusterId, sourceDatabase, targetDatabase,
                strictness == null ? ValidationStrictness.REPORT : strictness, json(includePatterns),
                json(excludePatterns),
                UUID.randomUUID().toString(), mode, sample, percentage, ttl, large, deep, chunk, elements,
                ValidationTaskStatus.CREATED, null,
                0, now, now));
    }

    public ValidationTask get(long id) {
        return validations.findTask(id).orElseThrow(() -> BusinessException.notFound("validationTask", id));
    }
    public List<ValidationTask> list() {
        return validations.findTasks();
    }
    public Optional<ValidationRun> latestRun(long id) {
        get(id);
        return validations.findLatestRun(id);
    }
    public PageResult<ValidationDifference> differences(long taskId, int page, int size) {
        get(taskId);
        return validations.findLatestRun(taskId).map(run -> validations.findDifferences(run.id(), page, size))
                .orElse(new PageResult<>(List.of(), 0, page, size));
    }

    @Transactional
    public ValidationTask start(long id, long version, String requestKey) {
        ValidationTask task = get(id);
        if (!(task.status() == ValidationTaskStatus.CREATED || task.status() == ValidationTaskStatus.READY
                || task.status() == ValidationTaskStatus.FAILED || task.status() == ValidationTaskStatus.INCONCLUSIVE))
            throw new BusinessException("VALIDATION_INVALID_STATE",
                    "validation task cannot be started from " + task.status());
        validations.findActiveTaskForPair(task.sourceClusterId(), task.targetClusterId()).ifPresent(active -> {
            throw new BusinessException("VALIDATION_CLUSTER_BUSY",
                    "validation task " + active.taskNo() + " is already running on this cluster pair");
        });
        runner.checkCapacity("DATA_VALIDATION");
        ValidationTask changed = replace(task, ValidationTaskStatus.CHECKING, null);
        if (!validations.updateTask(changed, version))
            throw new BusinessException("VERSION_CONFLICT", "validation task version changed");
        runner.submitAfterCommit("DATA_VALIDATION", () -> execute(id));
        return get(id);
    }

    @Transactional
    public ValidationTask cancel(long id, long version) {
        ValidationTask task = get(id);
        if (task.status() == ValidationTaskStatus.PASSED || task.status() == ValidationTaskStatus.CANCELLED)
            throw new BusinessException("VALIDATION_INVALID_STATE", "validation task is already terminal");
        ValidationTask changed = replace(task, ValidationTaskStatus.CANCELLED, null);
        if (!validations.updateTask(changed, version))
            throw new BusinessException("VERSION_CONFLICT", "validation task version changed");
        return get(id);
    }

    /**
     * Runs on the instance that received the start request, via {@link LocalJobRunner}. Deliberately
     * NOT transactional: a run spans minutes to hours, so every state change commits on its own
     * (progress, liveness and buffered differences publish on a periodic cadence and no row lock
     * outlives a single statement); any failure finalizes run and task here because there is no job
     * queue left to notice the run died.
     */
    public void execute(long id) {
        ValidationRun run = null;
        Result result = null;
        try {
            ValidationTask task = get(id);
            if (task.status() == ValidationTaskStatus.CANCELLED)
                return;
            if (task.status() != ValidationTaskStatus.CHECKING)
                throw new BusinessException("VALIDATION_INVALID_STATE",
                        "validation task cannot run from " + task.status());
            ValidationTask running = replace(task, ValidationTaskStatus.RUNNING, null);
            if (!validations.updateTask(running, task.version()))
                throw new BusinessException("VALIDATION_INVALID_STATE", "validation task taken over by another run");
            ValidationTask effective = get(id);
            run = validations.saveRun(new ValidationRun(null, id,
                    "VRUN-" + UUID.randomUUID().toString().substring(0, 12), "RUNNING", 0, 0, 0, 0, 0, 0, 0,
                    Instant.now(), null, null));
            long planned = estimatePlanned(effective);
            run = validations.saveRun(new ValidationRun(run.id(), run.taskId(), run.runNo(), "RUNNING", planned, 0, 0,
                    0,
                    0, 0, 0, run.startedAt(), null, "{\"phase\":\"COMPARING\"}"));
            result = new Result();
            compare(effective, run, result);
            Result.Outcome outcome = result.outcome();
            flush(result);
            // A FULL walk that matched zero keys in a non-empty keyspace is not evidence of equality — it means
            // the include filter or the matcher produced nothing, so the verdict must stay inconclusive.
            boolean zeroScan = task.samplingMode() == ValidationSamplingMode.FULL && outcome.scanned() == 0
                    && run.plannedKeys() > 0;
            String finalStatus = zeroScan || outcome.inconclusive() > 0
                    || (task.strictness() == ValidationStrictness.STRICT
                            && (outcome.degraded() > 0 || outcome.unverifiable() > 0))
                                    ? "INCONCLUSIVE"
                                    : outcome.mismatch() ? "FAILED" : "PASSED";
            validations.saveRun(new ValidationRun(run.id(), id, run.runNo(), finalStatus, run.plannedKeys(),
                    outcome.scanned(),
                    outcome.compared(),
                    outcome.differenceCount(), outcome.degraded(), outcome.unverifiable(),
                    outcome.inconclusive(),
                    run.startedAt(),
                    Instant.now(),
                    "{\"strictGateEligible\":" + ("PASSED".equals(finalStatus) && outcome.degraded() == 0) + "}"));
            ValidationTaskStatus status = "PASSED".equals(finalStatus)
                    ? ValidationTaskStatus.PASSED
                    : "INCONCLUSIVE".equals(finalStatus)
                            ? ValidationTaskStatus.INCONCLUSIVE
                            : ValidationTaskStatus.FAILED;
            ValidationTask latest = get(id);
            validations.updateTask(replace(latest, status, null), latest.version());
        } catch (ValidationCancelledException cancelled) {
            flushQuietly(result);
            if (run != null)
                saveRun(run, "CANCELLED", result);
            log.info("validation task {} cancelled mid-run", id);
        } catch (RuntimeException error) {
            log.warn("validation task {} failed", id, error);
            flushQuietly(result);
            if (run != null)
                saveRun(run, "FAILED", result);
            failTask(id, abbreviate(error));
        }
    }

    /** Fails runs whose heartbeat went stale and tasks whose queued execution was lost. */
    public void recoverInterrupted() {
        Instant staleRun = Instant.now().minus(STALE_RUN);
        for (ValidationRun run : validations.findStaleRuns(staleRun))
            if (validations.failRun(run.id())) {
                failTask(run.taskId(), "run interrupted: executor lost");
                log.warn("recovered stale validation run {} of task {}", run.id(), run.taskId());
            }
        // updated_at is written by the DB clock, so the staleness cut is computed DB-side.
        for (ValidationTask task : validations.findStaleQueuedTasks((int) STALE_QUEUED.toMinutes())) {
            failTask(task.id(), "run interrupted: executor lost before start");
            log.warn("recovered stale queued validation task {}", task.id());
        }
    }

    private void saveRun(ValidationRun run, String status, Result result) {
        Result.Outcome outcome = result == null ? null : result.outcome();
        validations.saveRun(new ValidationRun(run.id(), run.taskId(), run.runNo(), status, run.plannedKeys(),
                outcome == null ? run.scannedKeys() : outcome.scanned(),
                outcome == null ? run.comparedKeys() : outcome.compared(),
                outcome == null ? run.differenceCount() : outcome.differenceCount(),
                outcome == null ? run.degradedCount() : outcome.degraded(),
                outcome == null ? run.unverifiableCount() : outcome.unverifiable(),
                outcome == null ? run.inconclusiveCount() : outcome.inconclusive(),
                run.startedAt(), Instant.now(), null));
    }

    private void failTask(long id, String error) {
        ValidationTask task = get(id);
        if (task.status() == ValidationTaskStatus.RUNNING || task.status() == ValidationTaskStatus.CHECKING)
            validations.updateTask(replace(task, ValidationTaskStatus.FAILED, error), task.version());
    }

    private static String abbreviate(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }

    private void compare(ValidationTask task, ValidationRun run, Result result) {
        KeyMatcher matcher = KeyMatcher.of(task.includePatternsJson(), task.excludePatternsJson());
        Set<String> sourceKeys = ConcurrentHashMap.newKeySet();
        scanShards(shards(task.sourceClusterId(), task.sourceDb()), sampleBudget(task), task,
                task.sourceClusterId(), task.sourceDb(), task.targetClusterId(), task.targetDb(), false, matcher,
                sourceKeys, run, result);
        if (task.strictness() == ValidationStrictness.STRICT)
            scanShards(shards(task.targetClusterId(), task.targetDb()), sampleBudget(task), task,
                    task.targetClusterId(), task.targetDb(), task.sourceClusterId(), task.sourceDb(), true, matcher,
                    sourceKeys, run, result);
    }

    /** Per-pass budget: COUNT gives each pass its own sampleLimit, other modes are unbounded. */
    private static long sampleBudget(ValidationTask task) {
        return task.samplingMode() == ValidationSamplingMode.COUNT ? task.sampleLimit() : Long.MAX_VALUE;
    }

    private List<RedisValidationPort.ScanShard> shards(long clusterId, int database) {
        List<RedisValidationPort.ScanShard> shards = redis.scanShards(clusterId, database);
        return shards.isEmpty() ? List.of(new RedisValidationPort.ScanShard("default", "")) : shards;
    }

    /** DBSIZE-based sample estimate — one command per shard instead of the old full-keyspace counting scan. */
    private long estimatePlanned(ValidationTask task) {
        long total = redis.countKeys(task.sourceClusterId(), task.sourceDb());
        return switch (task.samplingMode()) {
            case COUNT -> Math.min(total, task.sampleLimit());
            case PERCENTAGE -> Math.round(total * task.samplePercentage() / 100.0);
            case FULL -> total;
        };
    }

    /**
     * Scans every shard in parallel (bounded by shard-parallelism, inline for a single shard). Each shard gets an
     * exact quota of the budget so a COUNT sample covers the whole cluster instead of whichever masters a serial
     * cursor walk reaches first; the first failure cancels the siblings and propagates.
     */
    private void scanShards(List<RedisValidationPort.ScanShard> shards, long budget, ValidationTask task,
            long fromCluster, int fromDb, long toCluster, int toDb, boolean reverse, KeyMatcher matcher,
            Set<String> sourceKeys, ValidationRun run, Result result) {
        if (shards.size() == 1) {
            scanShard(shards.get(0), budget, task, fromCluster, fromDb, toCluster, toDb, reverse, matcher, sourceKeys,
                    run, result, new Ticker());
            return;
        }
        long base = budget / shards.size();
        long remainder = budget % shards.size();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(shards.size(), shardParallelism));
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int index = 0; index < shards.size(); index++) {
                int shardIndex = index;
                long shardQuota = base + (index < remainder ? 1 : 0);
                pending.add(executor.submit(() -> scanShard(shards.get(shardIndex), shardQuota,
                        task, fromCluster, fromDb, toCluster, toDb, reverse, matcher, sourceKeys, run, result,
                        new Ticker())));
            }
            for (Future<?> future : pending) {
                try {
                    future.get();
                } catch (ExecutionException error) {
                    pending.forEach(other -> other.cancel(true));
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException runtime)
                        throw runtime;
                    throw new IllegalStateException("validation shard scan failed", cause);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("validation shard scan interrupted", error);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void scanShard(RedisValidationPort.ScanShard shard, long quota, ValidationTask task, long fromCluster,
            int fromDb, long toCluster, int toDb, boolean reverse, KeyMatcher matcher, Set<String> sourceKeys,
            ValidationRun run, Result result, Ticker ticker) {
        String cursor = "0";
        long scanned = 0;
        do {
            RedisValidationPort.ScanPage page = "default".equals(shard.id())
                    ? redis.scan(fromCluster, fromDb, cursor, 500)
                    : redis.scan(fromCluster, fromDb, shard.id(), cursor, 500);
            cursor = page.nextCursor();
            List<Selected> selected = new ArrayList<>();
            for (RedisValidationPort.ValidationKey key : page.keys()) {
                if (scanned >= quota)
                    break;
                if (!matcher.matches(key.bytes()))
                    continue;
                String hash = digest(key.bytes());
                if (task.samplingMode() == ValidationSamplingMode.PERCENTAGE
                        && !inPercentageSample(hash, task.sampleSeed(), task.samplePercentage()))
                    continue;
                if (reverse && sourceKeys.contains(hash))
                    continue;
                if (!reverse)
                    sourceKeys.add(hash);
                scanned++;
                result.scannedPlus();
                selected.add(new Selected(key.bytes(), hash));
            }
            compareBatch(task, fromCluster, fromDb, toCluster, toDb, selected, reverse, run.id(), result);
            if (scanned >= quota)
                return;
            ticker.tick(run, task.id(), result);
        } while (!"0".equals(cursor) && scanned < quota);
    }

    private record Selected(byte[] bytes, String hash) {
    }

    /**
     * Compares a whole page in two pipelined batches (source side, then target side); falls back to per-key
     * inspection when an adapter returns a misaligned result, which also keeps the single-key path exercised.
     */
    private void compareBatch(ValidationTask task, long sourceCluster, int sourceDb, long toCluster, int toDb,
            List<Selected> selected, boolean reverse, long runId, Result result) {
        if (selected.isEmpty())
            return;
        List<byte[]> keys = selected.stream().map(Selected::bytes).toList();
        List<Optional<RedisValidationPort.ValidationValue>> source = redis.inspectBatch(sourceCluster, sourceDb,
                keys, task);
        List<Optional<RedisValidationPort.ValidationValue>> target = redis.inspectBatch(toCluster, toDb, keys, task);
        if (source.size() != keys.size() || target.size() != keys.size()) {
            for (Selected item : selected)
                compareKey(task, toCluster, toDb, item.bytes(), item.hash(), reverse, runId, result);
            return;
        }
        for (int index = 0; index < selected.size(); index++)
            compareValues(task, selected.get(index).bytes(), selected.get(index).hash(), reverse, runId, result,
                    source.get(index), target.get(index));
    }

    /** Publishes liveness and aborts promptly when the task is cancelled while running. */
    private void heartbeat(long runId, long taskId) {
        validations.touchRun(runId);
        if (get(taskId).status() == ValidationTaskStatus.CANCELLED)
            throw new ValidationCancelledException();
    }

    private void saveProgress(ValidationRun run, Result result) {
        Result.Outcome outcome = result.outcome();
        validations.saveRun(new ValidationRun(run.id(), run.taskId(), run.runNo(), "RUNNING", run.plannedKeys(),
                outcome.scanned(),
                outcome.compared(), outcome.differenceCount(), outcome.degraded(), outcome.unverifiable(),
                outcome.inconclusive(),
                run.startedAt(), null, "{\"progress\":true}"));
    }

    /** Persists whatever differences are still buffered, so the list is queryable while the run is live. */
    private void flush(Result result) {
        List<ValidationDifference> batch = result.drain();
        if (!batch.isEmpty())
            validations.saveDifferences(batch);
    }

    /** Best-effort flush on the cancel/failure paths — the run is already dying, a DB hiccup must not mask why. */
    private void flushQuietly(Result result) {
        if (result == null)
            return;
        try {
            flush(result);
        } catch (RuntimeException error) {
            log.warn("could not flush buffered validation differences: {}", error.getMessage());
        }
    }

    /**
     * Shared across shard threads: heartbeat, cancellation, progress and difference flushes publish on a time
     * cadence instead of per page — parallel shards finish pages far faster than the old serial loop, and
     * hammering the run row per page would just trade Redis time for database time. Draining the difference
     * buffer here also keeps platform memory bounded on keyspaces that diff heavily.
     */
    private final class Ticker {
        private long lastNanos = System.nanoTime();
        synchronized void tick(ValidationRun run, long taskId, Result result) {
            long now = System.nanoTime();
            if (now - lastNanos < PROGRESS_INTERVAL.toNanos())
                return;
            lastNanos = now;
            heartbeat(run.id(), taskId);
            saveProgress(run, result);
            flush(result);
        }
    }

    private void compareKey(ValidationTask task, long otherCluster, int otherDb, byte[] key, String hash,
            boolean reverse,
            long runId, Result result) {
        Optional<RedisValidationPort.ValidationValue> source = redis.inspect(
                reverse ? task.targetClusterId() : task.sourceClusterId(),
                reverse ? task.targetDb() : task.sourceDb(), key, task);
        Optional<RedisValidationPort.ValidationValue> target = redis.inspect(otherCluster, otherDb, key, task);
        compareValues(task, key, hash, reverse, runId, result, source, target);
    }

    private void compareValues(ValidationTask task, byte[] key, String hash, boolean reverse, long runId,
            Result result, Optional<RedisValidationPort.ValidationValue> source,
            Optional<RedisValidationPort.ValidationValue> target) {
        if (source.isEmpty()) {
            result.inconclusivePlus();
            return;
        }
        if (target.isEmpty()) {
            result.add(difference(runId,
                    reverse ? ValidationDifferenceType.EXTRA_TARGET : ValidationDifferenceType.MISSING_TARGET,
                    hash, key, source.get(), null, "EXISTENCE", null));
            return;
        }
        RedisValidationPort.ValidationValue left = source.get();
        RedisValidationPort.ValidationValue right = target.get();
        if (!left.type().equals(right.type())) {
            result.add(difference(runId, ValidationDifferenceType.TYPE_DIFF, hash, key, left, right, "TYPE", null));
            return;
        }
        if (left.degraded() || right.degraded()) {
            String reason = left.degraded() ? left.degradedReason() : right.degradedReason();
            ValidationDifferenceType type = "CHANGED_DURING_READ".equals(reason)
                    ? ValidationDifferenceType.INCONCLUSIVE_CHANGED_DURING_READ
                    : "UNSUPPORTED_TYPE".equals(reason)
                            ? ValidationDifferenceType.UNVERIFIABLE_UNSUPPORTED_TYPE
                            : ValidationDifferenceType.LARGE_KEY_DEGRADED;
            result.add(difference(runId, type, hash, key, left, right, "METADATA", reason));
            if (type == ValidationDifferenceType.INCONCLUSIVE_CHANGED_DURING_READ)
                result.inconclusivePlus();
            else if (type == ValidationDifferenceType.UNVERIFIABLE_UNSUPPORTED_TYPE)
                result.unverifiablePlus();
            else
                result.degradedPlus();
            return;
        }
        if (Math.abs(left.ttlSeconds() - right.ttlSeconds()) > task.ttlToleranceSeconds())
            result.add(difference(runId, ValidationDifferenceType.TTL_DIFF, hash, key, left, right, "TTL", null));
        if (!Objects.equals(left.digest(), right.digest()))
            if (confirmedByRecheck(task, key, reverse))
                result.add(difference(runId, ValidationDifferenceType.VALUE_DIFF, hash, key, left, right,
                        "SEMANTIC_DIGEST", null));
            else
                result.inconclusivePlus(); // changed between the two batch reads, not real drift
        result.comparedPlus();
    }

    /**
     * One fresh single-key read per side after a digest mismatch: a value written between the source batch and
     * the target batch would otherwise read as drift. Only a still-confirmed mismatch becomes a VALUE_DIFF.
     */
    private boolean confirmedByRecheck(ValidationTask task, byte[] key, boolean reverse) {
        Optional<RedisValidationPort.ValidationValue> left = redis.inspect(
                reverse ? task.targetClusterId() : task.sourceClusterId(),
                reverse ? task.targetDb() : task.sourceDb(), key, task);
        Optional<RedisValidationPort.ValidationValue> right = redis.inspect(
                reverse ? task.sourceClusterId() : task.targetClusterId(),
                reverse ? task.sourceDb() : task.targetDb(), key, task);
        return left.isPresent() && right.isPresent() && !left.get().degraded() && !right.get().degraded()
                && !Objects.equals(left.get().digest(), right.get().digest());
    }

    private static ValidationDifference difference(long runId, ValidationDifferenceType type, String hash, byte[] key,
            RedisValidationPort.ValidationValue source, RedisValidationPort.ValidationValue target, String level,
            String reason) {
        return new ValidationDifference(null, runId, type, hash, keyName(key),
                source == null ? null : source.type(),
                source == null ? null : source.size(), target == null ? null : target.size(),
                source == null ? null : source.ttlSeconds(), target == null ? null : target.ttlSeconds(), level,
                reason,
                Instant.now());
    }
    private static String keyName(byte[] key) {
        String value = new String(key, StandardCharsets.UTF_8);
        return value.chars().anyMatch(Character::isISOControl)
                ? "base64:" + Base64.getEncoder().encodeToString(key)
                : value;
    }
    private RedisCluster cluster(long id) {
        return clusters.findById(id).orElseThrow(() -> BusinessException.notFound("cluster", id));
    }
    private static int database(RedisCluster cluster, Integer value, String field) {
        if (cluster.mode() == ClusterMode.CLUSTER)
            return 0;
        if (value == null || value < 0)
            throw new BusinessException("VALIDATION_DATABASE_REQUIRED",
                    field + " must be provided for non-cluster Redis");
        return value;
    }
    private static long bounded(Long value, long defaultValue, long min, long max, String field) {
        long result = value == null ? defaultValue : value;
        if (result < min || result > max)
            throw new BusinessException("VALIDATION_INVALID_LIMIT", field + " must be between " + min + " and " + max);
        return result;
    }
    private static String json(List<String> values) {
        // an empty list must serialize as [], not [""] — an empty-string pattern matches nothing
        return values == null || values.isEmpty() ? "[]" : "[\"" + String.join("\",\"", values) + "\"]";
    }
    /**
     * Glob include/exclude patterns compiled once per run — the old path rebuilt a regex per key across the whole
     * keyspace.
     */
    private record KeyMatcher(List<Pattern> include, List<Pattern> exclude) {
        static KeyMatcher of(String includes, String excludes) {
            return new KeyMatcher(patterns(includes, true).stream().map(KeyMatcher::compile).toList(),
                    patterns(excludes, false).stream().map(KeyMatcher::compile).toList());
        }
        private static Pattern compile(String pattern) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*')
                    regex.append(".*");
                else if (c == '?')
                    regex.append('.');
                else if ("\\.^$|()[]{}+".indexOf(c) >= 0)
                    regex.append('\\').append(c);
                else
                    regex.append(c);
            }
            return Pattern.compile(regex.append('$').toString());
        }
        boolean matches(byte[] key) {
            String value = new String(key, StandardCharsets.UTF_8);
            return include.stream().anyMatch(pattern -> pattern.matcher(value).matches())
                    && exclude.stream().noneMatch(pattern -> pattern.matcher(value).matches());
        }
    }
    private static List<String> patterns(String json, boolean defaultAll) {
        if (json == null || json.length() < 3)
            return defaultAll ? List.of("*") : List.of();
        String body = json.substring(1, json.length() - 1);
        if (body.isBlank())
            return defaultAll ? List.of("*") : List.of();
        // JSON columns re-serialize arrays with ", " between elements, so the separator must tolerate whitespace.
        return Arrays.stream(body.split("\\\"\\s*,\\s*\\\"")).map(item -> item.replace("\"", "").trim()).toList();
    }
    private static final class Result {
        long scanned, compared, degraded, unverifiable, inconclusive;
        /** Cumulative total across flushed batches plus the buffer — the run row reports this live. */
        long differenceCount;
        /** Any true mismatch type seen so far; drives the FAILED verdict without replaying the difference list. */
        boolean mismatch;
        final List<ValidationDifference> differences = new ArrayList<>();
        synchronized void scannedPlus() {
            scanned++;
        }
        synchronized void comparedPlus() {
            compared++;
        }
        synchronized void degradedPlus() {
            degraded++;
        }
        synchronized void unverifiablePlus() {
            unverifiable++;
        }
        synchronized void inconclusivePlus() {
            inconclusive++;
        }
        synchronized void add(ValidationDifference difference) {
            differences.add(difference);
            differenceCount++;
            switch (difference.differenceType()) {
                case MISSING_TARGET, EXTRA_TARGET, TYPE_DIFF, VALUE_DIFF, TTL_DIFF -> mismatch = true;
                default -> {
                }
            }
        }
        /** Detaches the buffered differences for a flush; counters survive, so the verdict stays correct. */
        synchronized List<ValidationDifference> drain() {
            if (differences.isEmpty())
                return List.of();
            List<ValidationDifference> batch = List.copyOf(differences);
            differences.clear();
            return batch;
        }
        synchronized Outcome outcome() {
            return new Outcome(scanned, compared, degraded, unverifiable, inconclusive, differenceCount, mismatch);
        }
        /** Counters only, no list copy — differences live in the buffer until flushed to the database. */
        record Outcome(long scanned, long compared, long degraded, long unverifiable, long inconclusive,
                long differenceCount, boolean mismatch) {
        }
    }
    private static boolean inPercentageSample(String keyHash, String seed, double percentage) {
        String material = digest((seed + ':' + keyHash).getBytes(StandardCharsets.UTF_8));
        long bucket = Long.parseLong(material.substring(0, 8), 16) % 10_000;
        return bucket < Math.round(percentage * 100);
    }
    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static ValidationTask replace(ValidationTask task, ValidationTaskStatus status, String error) {
        return new ValidationTask(task.id(), task.taskNo(), task.syncTaskId(), task.sourceClusterId(),
                task.targetClusterId(),
                task.sourceDb(), task.targetDb(), task.strictness(), task.includePatternsJson(),
                task.excludePatternsJson(),
                task.sampleSeed(), task.samplingMode(), task.sampleLimit(), task.samplePercentage(),
                task.ttlToleranceSeconds(), task.largeKeyThresholdBytes(),
                task.maxDeepCompareBytes(), task.chunkBytes(), task.maxElementsPerKey(), status, error, task.version(),
                task.createdAt(), Instant.now());
    }
    /** Signals a user cancellation observed mid-run; the task row is already CANCELLED by then. */
    private static final class ValidationCancelledException extends RuntimeException {
    }
}
