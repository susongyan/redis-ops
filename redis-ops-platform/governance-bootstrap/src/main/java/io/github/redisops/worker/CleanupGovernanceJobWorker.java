package io.github.redisops.worker;

import io.github.redisops.application.alert.AlertService;
import io.github.redisops.application.governance.CleanupGovernanceService;
import io.github.redisops.domain.governance.*;
import io.github.redisops.domain.job.*;
import io.github.redisops.domain.validation.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class CleanupGovernanceJobWorker {
    private static final Pattern ID = Pattern.compile("\\\"taskId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final CleanupGovernanceService service;
    private final CleanupGovernanceRepository repository;
    private final RedisValidationPort redis;
    private final AlertService alerts;
    private final String owner;
    public CleanupGovernanceJobWorker(JobRepository jobs, CleanupGovernanceService service,
            CleanupGovernanceRepository repository, RedisValidationPort redis, AlertService alerts,
            @Value("${worker.instance-id:${HOSTNAME:local-worker}}") String instance) {
        this.jobs = jobs;
        this.service = service;
        this.repository = repository;
        this.redis = redis;
        this.alerts = alerts;
        owner = instance + "-cleanup";
    }
    @Scheduled(fixedDelayString = "${worker.cleanup-governance.poll-interval-ms:1000}")
    public void poll() {
        String lease = owner + ":" + UUID.randomUUID();
        jobs.claimNext("CLEANUP_GOVERNANCE", lease, Duration.ofMinutes(10)).ifPresent(job -> {
            try {
                var m = ID.matcher(job.payload());
                if (!m.find())
                    throw new IllegalArgumentException("cleanup task id missing");
                execute(Long.parseLong(m.group(1)), job.payload().contains("APPLY"));
                jobs.complete(job.id(), lease);
            } catch (RuntimeException error) {
                try {
                    var m = ID.matcher(job.payload());
                    if (m.find()) {
                        long id = Long.parseLong(m.group(1));
                        var task = service.get(id);
                        if (task.status() == TtlGovernanceStatus.RUNNING
                                || task.status() == TtlGovernanceStatus.DRY_RUN)
                            service.transition(id, task.version(), TtlGovernanceStatus.FAILED, task.approvalStatus(),
                                    task.approvalNote());
                        alerts.trigger("CLEANUP_GOVERNANCE_FAILED", "CLEANUP_GOVERNANCE", Long.toString(id), 1d,
                                "Cleanup governance task failed closed");
                    }
                } catch (RuntimeException ignored) {
                }
                jobs.retryOrFail(job.id(), lease, "cleanup governance execution failed");
            }
        });
    }
    private void execute(long id, boolean apply) {
        CleanupGovernanceTask task = service.get(id);
        if ((!apply && task.status() != TtlGovernanceStatus.DRY_RUN)
                || (apply && task.status() != TtlGovernanceStatus.RUNNING))
            return;
        var run = repository.latestRun(id).orElse(null);
        if (run == null)
            run = save(new CleanupGovernanceRun(null, id,
                    "CRUN-" + UUID.randomUUID().toString().substring(0, 12), TtlGovernanceStatus.RUNNING,
                    redis.countKeys(task.clusterId(), task.databaseNo()), 0, 0, 0, 0, 0, Instant.now(), null, null));
        long scanned = run.scannedKeys(), candidates = run.candidateKeys(), deleted = run.deletedKeys(),
                skipped = run.skippedKeys(), failed = run.failedKeys();
        var probe = new io.github.redisops.domain.validation.ValidationTask(null, "cleanup-probe", null,
                task.clusterId(), task.clusterId(), task.databaseNo(), task.databaseNo(), ValidationStrictness.REPORT,
                "[]", "[]", "cleanup", ValidationSamplingMode.COUNT, 1, null, 0, 64L * 1024 * 1024, 1, 1024, 1,
                ValidationTaskStatus.CREATED, null, 0, Instant.now(), Instant.now());
        for (var shard : redis.scanShards(task.clusterId(), task.databaseNo())) {
            var checkpoint = repository.checkpoint(run.id(), shard.id()).orElse(null);
            String cursor = checkpoint == null ? "0" : checkpoint.cursor();
            do {
                task = service.get(id);
                if (task.status() == TtlGovernanceStatus.PAUSED || task.status() == TtlGovernanceStatus.CANCELLED)
                    return;
                var page = redis.scan(task.clusterId(), task.databaseNo(), shard.id(), cursor, 200);
                cursor = page.nextCursor();
                for (var key : page.keys()) {
                    if (scanned >= task.impactLimit())
                        break;
                    String name = new String(key.bytes(), StandardCharsets.UTF_8);
                    if (!matches(task.includePattern(), name))
                        continue;
                    scanned++;
                    if (redis.inspect(task.clusterId(), task.databaseNo(), key.bytes(), probe).isEmpty()) {
                        skipped++;
                        continue;
                    }
                    candidates++;
                    if (apply) {
                        if (redis.unlinkIfPresent(task.clusterId(), task.databaseNo(), key.bytes()))
                            deleted++;
                        else
                            skipped++;
                    }
                }
                repository.saveCheckpoint(new CleanupGovernanceCheckpoint(run.id(), shard.id(), cursor, scanned,
                        "0".equals(cursor) ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.RUNNING,
                        Instant.now()));
                run = save(new CleanupGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.RUNNING,
                        run.plannedKeys(), scanned, candidates, deleted, skipped, failed, run.startedAt(), null, null));
                rateLimit(page.keys().size(), task.scanRatePerSecond());
            } while (!"0".equals(cursor) && scanned < task.impactLimit());
        }
        save(new CleanupGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.COMPLETED,
                run.plannedKeys(), scanned, candidates, deleted, skipped, failed, run.startedAt(), Instant.now(),
                null));
        service.transition(id, service.get(id).version(),
                apply ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.AWAITING_APPROVAL,
                apply ? TtlApprovalStatus.APPROVED : TtlApprovalStatus.PENDING, service.get(id).approvalNote());
    }
    private CleanupGovernanceRun save(CleanupGovernanceRun run) {
        return repository.saveRun(run);
    }
    private static boolean matches(String pattern, String key) {
        return "*".equals(pattern) || key.matches(pattern.replace(".", "\\.").replace("*", ".*"));
    }
    private static void rateLimit(int count, int rate) {
        try {
            Thread.sleep(Math.max(0, count * 1000L / Math.max(1, rate)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup governance interrupted", e);
        }
    }
}
