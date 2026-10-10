package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.job.*;
import io.github.susongyan.redisops.platform.domain.validation.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
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
    private final ReadOnlyScanPort reads;
    private final AlertService alerts;
    private final String owner;
    public TtlGovernanceJobWorker(JobRepository jobs, TtlGovernanceService service, TtlGovernanceRepository repository,
            RedisValidationPort redis, ReadOnlyScanPort reads, AlertService alerts,
            @Value("${platform.jobs.instance-id:${HOSTNAME:local-platform}}") String instance) {
        this.jobs = jobs;
        this.service = service;
        this.repository = repository;
        this.redis = redis;
        this.reads = reads;
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
        var topology = apply ? null : reads.topology(task.clusterId(), task.databaseNo());
        var targets = apply
                ? redis.scanShards(task.clusterId(), task.databaseNo()).stream()
                        .map(s -> new ReadOnlyScanPort.Shard(s.id(), "")).toList()
                : topology.shards();
        var run = repository.latestRun(id).orElse(null);
        if (run == null || (!command.resume() && !run.runNo().equals("TRUN-" + jobId))
                || run.status() != command.phase()) {
            run = saveRun(new TtlGovernanceRun(null, task.id(), "TRUN-" + jobId,
                    command.phase(),
                    apply
                            ? redis.countKeys(task.clusterId(), task.databaseNo())
                            : reads.count(task.clusterId(), task.databaseNo(), topology),
                    0, 0, 0, 0, 0,
                    java.time.Instant.now(), null, null));
        }
        long scanned = run.scannedKeys(), candidates = run.candidateKeys(), applied = run.appliedKeys();
        long skipped = run.skippedKeys(), failed = run.failedKeys();
        var probe = new io.github.susongyan.redisops.platform.domain.validation.ValidationTask(null, "ttl-probe", null,
                task.clusterId(),
                task.clusterId(), task.databaseNo(), task.databaseNo(), ValidationStrictness.REPORT, "[]", "[]", "ttl",
                ValidationSamplingMode.COUNT, 1, null, 0, 64L * 1024 * 1024, 1, 1024, 1,
                ValidationTaskStatus.CREATED, null, 0, java.time.Instant.now(), java.time.Instant.now());
        if (!apply) {
            Set<String> expected = new HashSet<>();
            for (var shard : targets)
                expected.add(topology.checkpointId(shard));
            var saved = repository.checkpoints(run.id());
            if (saved.isEmpty() && scanned == 0) {
                for (String target : expected)
                    repository.saveCheckpoint(new TtlGovernanceCheckpoint(run.id(), target, "0", 0,
                            TtlGovernanceStatus.RUNNING, java.time.Instant.now()));
            } else if (!expected.equals(new HashSet<>(saved.stream().map(TtlGovernanceCheckpoint::shardId).toList())))
                throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        }
        for (var shard : targets) {
            String shardId = apply ? shard.id() : topology.checkpointId(shard);
            var checkpoint = repository.checkpoint(run.id(), shardId).orElse(null);
            if (checkpoint != null && checkpoint.status() == TtlGovernanceStatus.COMPLETED)
                continue;
            String cursor = checkpoint == null ? "0" : checkpoint.cursor();
            do {
                task = service.get(id);
                if (!command.accepts(task.version(), task.status())
                        || !jobs.renew(jobId, lease, Duration.ofMinutes(10)))
                    return;
                if (!apply && !topology.fingerprint()
                        .equals(reads.topology(task.clusterId(), task.databaseNo()).fingerprint()))
                    throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
                ReadOnlyScanPort.Page page;
                if (apply) {
                    var primaryPage = redis.scan(task.clusterId(), task.databaseNo(), shard.id(), cursor, 200);
                    page = new ReadOnlyScanPort.Page(primaryPage.nextCursor(),
                            primaryPage.keys().stream().map(RedisValidationPort.ValidationKey::bytes).toList());
                } else
                    page = reads.scan(task.clusterId(), task.databaseNo(), shard, cursor, 200);
                cursor = page.cursor();
                for (var key : page.keys()) {
                    if (scanned >= task.maxKeys())
                        break;
                    if (key == null)
                        continue;
                    String name = new String(key, StandardCharsets.UTF_8);
                    if (!matches(task.includePattern(), name))
                        continue;
                    scanned++;
                    var value = apply
                            ? redis.inspect(task.clusterId(), task.databaseNo(), key, probe).orElse(null)
                            : null;
                    long ttl = apply
                            ? (value == null ? -2 : value.ttlSeconds())
                            : reads.metadata(task.clusterId(), task.databaseNo(), shard, key).ttlSeconds();
                    if (ttl != -1) {
                        skipped++;
                        continue;
                    }
                    candidates++;
                    if (!apply)
                        continue;
                    var result = redis.applyTtlIfUnchanged(task.clusterId(), task.databaseNo(), key, -1,
                            task.targetTtlSeconds());
                    if (result.applied())
                        applied++;
                    else
                        skipped++;
                }
                var savedCheckpoint = new TtlGovernanceCheckpoint(run.id(), shardId, cursor, scanned,
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
        if (!apply && !topology.fingerprint().equals(reads.topology(task.clusterId(), task.databaseNo()).fingerprint()))
            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
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
