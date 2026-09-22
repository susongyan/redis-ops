package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.job.*;
import io.github.susongyan.redisops.platform.domain.validation.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
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
            @Value("${platform.jobs.instance-id:${HOSTNAME:local-platform}}") String instance) {
        this.jobs = jobs;
        this.service = service;
        this.repository = repository;
        this.redis = redis;
        this.alerts = alerts;
        owner = instance + "-ttl";
    }
    @Scheduled(fixedDelayString = "${platform.jobs.ttl-governance.poll-interval-ms:1000}")
    public void poll() {
        String lease = owner + ":" + UUID.randomUUID();
        jobs.claimNext("TTL_GOVERNANCE", lease, Duration.ofMinutes(10)).ifPresent(job -> {
            try {
                execute(GovernanceJobCommand.parse(job.payload()), job.id(), lease);
                jobs.complete(job.id(), lease);
            } catch (RuntimeException error) {
                try {
                    var failedMatcher = ID.matcher(job.payload());
                    if (failedMatcher.find()) {
                        long failedId = Long.parseLong(failedMatcher.group(1));
                        var failedTask = service.get(failedId);
                        if (GovernanceJobCommand.parse(job.payload()).accepts(failedTask.version(), failedTask.status())
                                && jobs.renew(job.id(), lease, Duration.ofMinutes(10)))
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
    private void execute(GovernanceJobCommand command, long jobId, String lease) {
        long id = command.taskId();
        boolean apply = command.apply();
        TtlGovernanceTask task = service.get(id);
        if (!command.accepts(task.version(), task.status()))
            return;
        var run = repository.latestRun(id).orElse(null);
        if (run == null || (!command.resume() && !run.runNo().equals("TRUN-" + jobId))
                || run.status() != command.phase()) {
            run = saveRun(new TtlGovernanceRun(null, task.id(), "TRUN-" + jobId,
                    command.phase(), redis.countKeys(task.clusterId(), task.databaseNo()), 0, 0, 0, 0, 0,
                    java.time.Instant.now(), null, null));
        }
        long scanned = run.scannedKeys(), candidates = run.candidateKeys(), applied = run.appliedKeys();
        long skipped = run.skippedKeys(), failed = run.failedKeys();
        var probe = new io.github.susongyan.redisops.platform.domain.validation.ValidationTask(null, "ttl-probe", null,
                task.clusterId(),
                task.clusterId(), task.databaseNo(), task.databaseNo(), ValidationStrictness.REPORT, "[]", "[]", "ttl",
                ValidationSamplingMode.COUNT, 1, null, 0, 64L * 1024 * 1024, 1, 1024, 1,
                ValidationTaskStatus.CREATED, null, 0, java.time.Instant.now(), java.time.Instant.now());
        for (var shard : redis.scanShards(task.clusterId(), task.databaseNo())) {
            var checkpoint = repository.checkpoint(run.id(), shard.id()).orElse(null);
            if (checkpoint != null && checkpoint.status() == TtlGovernanceStatus.COMPLETED)
                continue;
            String cursor = checkpoint == null ? "0" : checkpoint.cursor();
            do {
                task = service.get(id);
                if (!command.accepts(task.version(), task.status())
                        || !jobs.renew(jobId, lease, Duration.ofMinutes(10)))
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
                var savedCheckpoint = new TtlGovernanceCheckpoint(run.id(), shard.id(), cursor, scanned,
                        "0".equals(cursor) ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.RUNNING,
                        java.time.Instant.now());
                run = new TtlGovernanceRun(run.id(), run.taskId(), run.runNo(), command.phase(),
                        run.plannedKeys(), scanned, candidates, applied, skipped, failed, run.startedAt(), null, null);
                if (!jobs.renew(jobId, lease, Duration.ofMinutes(10)))
                    return;
                run = service.saveProgress(run, savedCheckpoint);
                rateLimit(page.keys().size(), task.scanRatePerSecond(), command);
            } while (!"0".equals(cursor) && scanned < task.maxKeys());
        }
        task = service.get(id);
        if (!command.accepts(task.version(), task.status()) || !jobs.renew(jobId, lease, Duration.ofMinutes(10)))
            return;
        run = new TtlGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.COMPLETED,
                run.plannedKeys(), scanned, candidates, applied, skipped, failed, run.startedAt(),
                java.time.Instant.now(), null);
        service.completeRun(run, command.version(), apply);
    }
    private TtlGovernanceRun saveRun(TtlGovernanceRun run) {
        return repository.saveRun(run);
    }
    private static boolean matches(String pattern, String key) {
        return "*".equals(pattern) || key.matches(pattern.replace(".", "\\.").replace("*", ".*"));
    }
    private void rateLimit(int count, int rate, GovernanceJobCommand command) {
        if (rate <= 0)
            return;
        try {
            long remaining = Math.max(0, count * 1000L / rate);
            while (remaining > 0) {
                var task = service.get(command.taskId());
                if (!command.accepts(task.version(), task.status()))
                    return;
                long step = Math.min(200, remaining);
                Thread.sleep(step);
                remaining -= step;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ttl governance interrupted", e);
        }
    }
}
