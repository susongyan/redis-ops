package io.github.redisops.worker;

import io.github.redisops.application.alert.AlertService;
import io.github.redisops.application.governance.TtlGovernanceService;
import io.github.redisops.domain.governance.*;
import io.github.redisops.domain.job.*;
import io.github.redisops.domain.validation.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class TtlGovernanceJobWorker {
    private static final Pattern ID = Pattern.compile("\\\"taskId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final TtlGovernanceService service;
    private final TtlGovernanceRepository repository;
    private final RedisValidationPort redis;
    private final AlertService alerts;
    private final String owner;
    public TtlGovernanceJobWorker(JobRepository jobs, TtlGovernanceService service, TtlGovernanceRepository repository,
            RedisValidationPort redis, AlertService alerts,
            @Value("${worker.instance-id:${HOSTNAME:local-worker}}") String instance) {
        this.jobs = jobs;
        this.service = service;
        this.repository = repository;
        this.redis = redis;
        this.alerts = alerts;
        owner = instance + "-ttl";
    }
    @Scheduled(fixedDelayString = "${worker.ttl-governance.poll-interval-ms:1000}")
    public void poll() {
        String lease = owner + ":" + UUID.randomUUID();
        jobs.claimNext("TTL_GOVERNANCE", lease, Duration.ofMinutes(10)).ifPresent(job -> {
            try {
                var matcher = ID.matcher(job.payload());
                if (!matcher.find())
                    throw new IllegalArgumentException("ttl task id missing");
                long id = Long.parseLong(matcher.group(1));
                boolean apply = job.payload().contains("APPLY");
                execute(id, apply);
                jobs.complete(job.id(), lease);
            } catch (RuntimeException error) {
                try {
                    var failedMatcher = ID.matcher(job.payload());
                    if (failedMatcher.find()) {
                        long failedId = Long.parseLong(failedMatcher.group(1));
                        var failedTask = service.get(failedId);
                        if (failedTask.status() == TtlGovernanceStatus.RUNNING
                                || failedTask.status() == TtlGovernanceStatus.DRY_RUN)
                            service.transition(failedId, failedTask.version(), TtlGovernanceStatus.FAILED,
                                    failedTask.approvalStatus());
                    }
                } catch (RuntimeException ignored) {
                    // Keep the original worker failure bounded; the task remains observable for manual recovery.
                }
                try {
                    var failedMatcher = ID.matcher(job.payload());
                    if (failedMatcher.find())
                        alerts.trigger("TTL_GOVERNANCE_FAILED", "TTL_GOVERNANCE", failedMatcher.group(1), 1d,
                                "TTL governance task failed closed");
                } catch (RuntimeException ignored) {
                    // Alert delivery must not mask task failure handling.
                }
                jobs.retryOrFail(job.id(), lease, "ttl governance execution failed");
            }
        });
    }
    private void execute(long id, boolean apply) {
        TtlGovernanceTask task = service.get(id);
        if (task.status() == TtlGovernanceStatus.CANCELLED || task.status() == TtlGovernanceStatus.PAUSED)
            return;
        if ((!apply && task.status() != TtlGovernanceStatus.DRY_RUN)
                || (apply && task.status() != TtlGovernanceStatus.RUNNING))
            return;
        var run = repository.latestRun(id).orElse(null);
        if (run == null || run.status() != TtlGovernanceStatus.RUNNING) {
            run = saveRun(new TtlGovernanceRun(null, task.id(), "TRUN-" + UUID.randomUUID().toString().substring(0, 12),
                    TtlGovernanceStatus.RUNNING, redis.countKeys(task.clusterId(), task.databaseNo()), 0, 0, 0, 0, 0,
                    java.time.Instant.now(), null, null));
        }
        long scanned = run.scannedKeys(), candidates = run.candidateKeys(), applied = run.appliedKeys();
        long skipped = run.skippedKeys(), failed = run.failedKeys();
        var probe = new io.github.redisops.domain.validation.ValidationTask(null, "ttl-probe", null, task.clusterId(),
                task.clusterId(), task.databaseNo(), task.databaseNo(), ValidationStrictness.REPORT, "[]", "[]", "ttl",
                ValidationSamplingMode.COUNT, 1, null, 0, 64L * 1024 * 1024, 1, 1024, 1,
                ValidationTaskStatus.CREATED, null, 0, java.time.Instant.now(), java.time.Instant.now());
        for (var shard : redis.scanShards(task.clusterId(), task.databaseNo())) {
            var checkpoint = repository.checkpoint(run.id(), shard.id()).orElse(null);
            String cursor = checkpoint == null ? "0" : checkpoint.cursor();
            do {
                task = service.get(id);
                if (task.status() == TtlGovernanceStatus.CANCELLED || task.status() == TtlGovernanceStatus.PAUSED)
                    return;
                if ((!apply && task.status() != TtlGovernanceStatus.DRY_RUN)
                        || (apply && task.status() != TtlGovernanceStatus.RUNNING))
                    return;
                var page = redis.scan(task.clusterId(), task.databaseNo(), shard.id(), cursor, 200);
                cursor = page.nextCursor();
                for (var key : page.keys()) {
                    if (scanned >= task.maxKeys())
                        break;
                    String name = new String(key.bytes(), StandardCharsets.UTF_8);
                    if (!matches(task.includePattern(), name))
                        continue;
                    scanned++;
                    var value = redis.inspect(task.clusterId(), task.databaseNo(), key.bytes(), probe);
                    if (value.isEmpty() || value.get().ttlSeconds() != -1) {
                        skipped++;
                        continue;
                    }
                    candidates++;
                    if (!apply)
                        continue;
                    var result = redis.applyTtlIfUnchanged(task.clusterId(), task.databaseNo(), key.bytes(), -1,
                            task.targetTtlSeconds());
                    if (result.applied())
                        applied++;
                    else
                        skipped++;
                }
                repository.saveCheckpoint(new TtlGovernanceCheckpoint(run.id(), shard.id(), cursor, scanned,
                        "0".equals(cursor) ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.RUNNING,
                        java.time.Instant.now()));
                run = new TtlGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.RUNNING,
                        run.plannedKeys(), scanned, candidates, applied, skipped, failed, run.startedAt(), null, null);
                run = saveRun(run);
                rateLimit(page.keys().size(), task.scanRatePerSecond());
            } while (!"0".equals(cursor) && scanned < task.maxKeys());
        }
        run = new TtlGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.COMPLETED,
                run.plannedKeys(), scanned, candidates, applied, skipped, failed, run.startedAt(),
                java.time.Instant.now(), null);
        saveRun(run);
        service.transition(id, service.get(id).version(),
                apply ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.AWAITING_APPROVAL,
                apply ? TtlApprovalStatus.APPROVED : TtlApprovalStatus.PENDING);
    }
    private TtlGovernanceRun saveRun(TtlGovernanceRun run) {
        return repository.saveRun(run);
    }
    private static boolean matches(String pattern, String key) {
        return "*".equals(pattern) || key.matches(pattern.replace(".", "\\.").replace("*", ".*"));
    }
    private static void rateLimit(int count, int rate) {
        if (rate <= 0)
            return;
        try {
            Thread.sleep(Math.max(0, count * 1000L / rate));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ttl governance interrupted", e);
        }
    }
}
