package io.github.redisops.application.validation;

import io.github.redisops.common.*;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.job.JobRepository;
import io.github.redisops.domain.validation.*;
import java.security.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationService {
    private static final long MAX_THRESHOLD = 1024L * 1024 * 1024;
    private final ValidationRepository validations;
    private final ClusterRepository clusters;
    private final JobRepository jobs;
    private final RedisValidationPort redis;

    public ValidationService(ValidationRepository validations, ClusterRepository clusters, JobRepository jobs,
            RedisValidationPort redis) {
        this.validations = validations;
        this.clusters = clusters;
        this.jobs = jobs;
        this.redis = redis;
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
        ValidationTask changed = replace(task, ValidationTaskStatus.CHECKING, null);
        if (!validations.updateTask(changed, version))
            throw new BusinessException("VERSION_CONFLICT", "validation task version changed");
        jobs.enqueue("DATA_VALIDATION", id, "{\"validationTaskId\":" + id + "}", requestKey);
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

    /** Called only by the leased platform worker. */
    @Transactional
    public void execute(long id) {
        ValidationTask task = get(id);
        if (task.status() == ValidationTaskStatus.CANCELLED)
            return;
        ValidationTask running = replace(task, ValidationTaskStatus.RUNNING, null);
        validations.updateTask(running, task.version());
        ValidationTask effective = get(id);
        ValidationRun run = validations.saveRun(new ValidationRun(null, id,
                "VRUN-" + UUID.randomUUID().toString().substring(0, 12), "RUNNING", 0, 0, 0, 0, 0, 0, 0,
                Instant.now(), null, null));
        long planned = countCandidates(effective);
        run = validations.saveRun(new ValidationRun(run.id(), run.taskId(), run.runNo(), "RUNNING", planned, 0, 0, 0,
                0, 0, 0, run.startedAt(), null, "{\"phase\":\"COMPARING\"}"));
        Result result = compare(effective, run);
        validations.saveDifferences(result.differences);
        boolean hasMismatch = result.differences.stream()
                .anyMatch(d -> d.differenceType() == ValidationDifferenceType.MISSING_TARGET
                        || d.differenceType() == ValidationDifferenceType.EXTRA_TARGET
                        || d.differenceType() == ValidationDifferenceType.TYPE_DIFF
                        || d.differenceType() == ValidationDifferenceType.VALUE_DIFF
                        || d.differenceType() == ValidationDifferenceType.TTL_DIFF);
        String finalStatus = result.inconclusive > 0 || (task.strictness() == ValidationStrictness.STRICT
                && (result.degraded > 0 || result.unverifiable > 0))
                        ? "INCONCLUSIVE"
                        : hasMismatch ? "FAILED" : "PASSED";
        validations.saveRun(new ValidationRun(run.id(), id, run.runNo(), finalStatus, run.plannedKeys(), result.scanned,
                result.compared,
                result.differences.size(), result.degraded, result.unverifiable, result.inconclusive, run.startedAt(),
                Instant.now(),
                "{\"strictGateEligible\":" + ("PASSED".equals(finalStatus) && result.degraded == 0) + "}"));
        ValidationTaskStatus status = "PASSED".equals(finalStatus)
                ? ValidationTaskStatus.PASSED
                : "INCONCLUSIVE".equals(finalStatus) ? ValidationTaskStatus.INCONCLUSIVE : ValidationTaskStatus.FAILED;
        ValidationTask latest = get(id);
        validations.updateTask(replace(latest, status, null), latest.version());
    }

    private Result compare(ValidationTask task, ValidationRun run) {
        Result result = new Result();
        Set<String> sourceKeys = new HashSet<>();
        scan(task, task.sourceClusterId(), task.sourceDb(), task.targetClusterId(), task.targetDb(), false, sourceKeys,
                run,
                result);
        if (task.strictness() == ValidationStrictness.STRICT)
            scan(task, task.targetClusterId(), task.targetDb(), task.sourceClusterId(), task.sourceDb(), true,
                    sourceKeys, run, result);
        return result;
    }

    private void scan(ValidationTask task, long fromCluster, int fromDb, long toCluster, int toDb, boolean reverse,
            Set<String> sourceKeys, ValidationRun run, Result result) {
        String cursor = "0";
        long totalLimit = task.samplingMode() == ValidationSamplingMode.FULL
                ? Long.MAX_VALUE
                : task.samplingMode() == ValidationSamplingMode.COUNT
                        ? task.strictness() == ValidationStrictness.STRICT
                                ? task.sampleLimit() * 2L
                                : task.sampleLimit()
                        : Long.MAX_VALUE;
        do {
            RedisValidationPort.ScanPage page = redis.scan(fromCluster, fromDb, cursor, 200);
            cursor = page.nextCursor();
            for (RedisValidationPort.ValidationKey key : page.keys()) {
                if (result.scanned >= totalLimit)
                    return;
                if (!matchesFilters(key.bytes(), task.includePatternsJson(), task.excludePatternsJson()))
                    continue;
                String hash = digest(key.bytes());
                if (task.samplingMode() == ValidationSamplingMode.PERCENTAGE
                        && !inPercentageSample(hash, task.sampleSeed(), task.samplePercentage()))
                    continue;
                if (reverse && sourceKeys.contains(hash))
                    continue;
                if (!reverse)
                    sourceKeys.add(hash);
                result.scanned++;
                compareKey(task, toCluster, toDb, key.bytes(), hash, reverse, run.id(), result);
            }
            saveProgress(run, result);
        } while (!"0".equals(cursor) && result.scanned < totalLimit);
    }

    private void saveProgress(ValidationRun run, Result result) {
        validations.saveRun(new ValidationRun(run.id(), run.taskId(), run.runNo(), "RUNNING", run.plannedKeys(),
                result.scanned,
                result.compared, result.differences.size(), result.degraded, result.unverifiable, result.inconclusive,
                run.startedAt(), null, "{\"progress\":true}"));
    }

    private long countCandidates(ValidationTask task) {
        String cursor = "0";
        long count = 0;
        do {
            RedisValidationPort.ScanPage page = redis.scan(task.sourceClusterId(), task.sourceDb(), cursor, 500);
            cursor = page.nextCursor();
            for (RedisValidationPort.ValidationKey key : page.keys()) {
                if (!matchesFilters(key.bytes(), task.includePatternsJson(), task.excludePatternsJson()))
                    continue;
                String hash = digest(key.bytes());
                if (task.samplingMode() == ValidationSamplingMode.PERCENTAGE
                        && !inPercentageSample(hash, task.sampleSeed(), task.samplePercentage()))
                    continue;
                count++;
                if (task.samplingMode() == ValidationSamplingMode.COUNT && count >= task.sampleLimit())
                    return count;
            }
        } while (!"0".equals(cursor));
        return count;
    }

    private void compareKey(ValidationTask task, long otherCluster, int otherDb, byte[] key, String hash,
            boolean reverse,
            long runId, Result result) {
        Optional<RedisValidationPort.ValidationValue> source = redis.inspect(
                reverse ? task.targetClusterId() : task.sourceClusterId(),
                reverse ? task.targetDb() : task.sourceDb(), key, task);
        Optional<RedisValidationPort.ValidationValue> target = redis.inspect(otherCluster, otherDb, key, task);
        if (source.isEmpty()) {
            result.inconclusive++;
            return;
        }
        if (target.isEmpty()) {
            add(result, runId,
                    reverse ? ValidationDifferenceType.EXTRA_TARGET : ValidationDifferenceType.MISSING_TARGET,
                    hash, key, source.get(), null, "EXISTENCE", null);
            return;
        }
        RedisValidationPort.ValidationValue left = source.get();
        RedisValidationPort.ValidationValue right = target.get();
        if (!left.type().equals(right.type())) {
            add(result, runId, ValidationDifferenceType.TYPE_DIFF, hash, key, left, right, "TYPE", null);
            return;
        }
        if (left.degraded() || right.degraded()) {
            String reason = left.degraded() ? left.degradedReason() : right.degradedReason();
            ValidationDifferenceType type = "CHANGED_DURING_READ".equals(reason)
                    ? ValidationDifferenceType.INCONCLUSIVE_CHANGED_DURING_READ
                    : "UNSUPPORTED_TYPE".equals(reason)
                            ? ValidationDifferenceType.UNVERIFIABLE_UNSUPPORTED_TYPE
                            : ValidationDifferenceType.LARGE_KEY_DEGRADED;
            add(result, runId, type, hash, key, left, right, "METADATA", reason);
            if (type == ValidationDifferenceType.INCONCLUSIVE_CHANGED_DURING_READ)
                result.inconclusive++;
            else if (type == ValidationDifferenceType.UNVERIFIABLE_UNSUPPORTED_TYPE)
                result.unverifiable++;
            else
                result.degraded++;
            return;
        }
        if (Math.abs(left.ttlSeconds() - right.ttlSeconds()) > task.ttlToleranceSeconds())
            add(result, runId, ValidationDifferenceType.TTL_DIFF, hash, key, left, right, "TTL", null);
        if (!Objects.equals(left.digest(), right.digest()))
            add(result, runId, ValidationDifferenceType.VALUE_DIFF, hash, key, left, right, "SEMANTIC_DIGEST", null);
        result.compared++;
    }

    private static void add(Result result, long runId, ValidationDifferenceType type, String hash, byte[] key,
            RedisValidationPort.ValidationValue source, RedisValidationPort.ValidationValue target, String level,
            String reason) {
        result.differences.add(new ValidationDifference(null, runId, type, hash, keyName(key),
                source == null ? null : source.type(),
                source == null ? null : source.size(), target == null ? null : target.size(),
                source == null ? null : source.ttlSeconds(), target == null ? null : target.ttlSeconds(), level, reason,
                Instant.now()));
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
        return values == null ? "[]" : "[\"" + String.join("\",\"", values) + "\"]";
    }
    private static boolean matchesFilters(byte[] key, String includes, String excludes) {
        String value = new String(key, StandardCharsets.UTF_8);
        List<String> include = patterns(includes, true);
        List<String> exclude = patterns(excludes, false);
        return include.stream().anyMatch(pattern -> glob(value, pattern))
                && exclude.stream().noneMatch(pattern -> glob(value, pattern));
    }
    private static List<String> patterns(String json, boolean defaultAll) {
        if (json == null || json.length() < 3)
            return defaultAll ? List.of("*") : List.of();
        String body = json.substring(1, json.length() - 1);
        if (body.isBlank())
            return defaultAll ? List.of("*") : List.of();
        return Arrays.stream(body.split("\\\",\\\"")).map(item -> item.replace("\"", "")).toList();
    }
    private static boolean glob(String value, String pattern) {
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
        return value.matches(regex.append('$').toString());
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
    private static final class Result {
        long scanned, compared, degraded, unverifiable, inconclusive;
        final List<ValidationDifference> differences = new ArrayList<>();
    }
}
