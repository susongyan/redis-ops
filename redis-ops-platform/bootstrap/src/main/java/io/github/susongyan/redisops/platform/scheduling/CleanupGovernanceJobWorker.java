package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService;
import io.github.susongyan.redisops.platform.domain.governance.*;
import io.github.susongyan.redisops.platform.domain.job.*;
import io.github.susongyan.redisops.platform.domain.validation.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
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
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CleanupGovernanceJobWorker {
    private static final Pattern ID = Pattern.compile("\\\"taskId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final CleanupGovernanceService service;
    private final CleanupGovernanceRepository repository;
    private final RedisValidationPort redis;
    private final ReadOnlyScanPort reads;
    private final AlertService alerts;
    private final String owner;
    public CleanupGovernanceJobWorker(JobRepository jobs, CleanupGovernanceService service,
            CleanupGovernanceRepository repository, RedisValidationPort redis, ReadOnlyScanPort reads,
            AlertService alerts,
            @Value("${platform.jobs.instance-id:${HOSTNAME:local-platform}}") String instance) {
        this.jobs = jobs;
        this.service = service;
        this.repository = repository;
        this.redis = redis;
        this.reads = reads;
        this.alerts = alerts;
        owner = instance + "-cleanup";
    }
    @Scheduled(fixedDelayString = "${platform.jobs.cleanup-governance.poll-interval-ms:1000}")
    public void poll() {
        String lease = owner + ":" + UUID.randomUUID();
        jobs.claimNext("CLEANUP_GOVERNANCE", lease, Duration.ofMinutes(10)).ifPresent(job -> {
            try {
                execute(GovernanceJobCommand.parse(job.payload()), job.id(), lease);
                jobs.complete(job.id(), lease);
            } catch (RuntimeException error) {
                try {
                    var m = ID.matcher(job.payload());
                    if (m.find()) {
                        long id = Long.parseLong(m.group(1));
                        var task = service.get(id);
                        if (GovernanceJobCommand.parse(job.payload()).accepts(task.version(), task.status())
                                && jobs.renew(job.id(), lease, Duration.ofMinutes(10)))
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
    private void execute(GovernanceJobCommand command, long jobId, String lease) {
        long id = command.taskId();
        boolean apply = command.apply();
        CleanupGovernanceTask task = service.get(id);
        if (!command.accepts(task.version(), task.status()))
            return;
        var topology = apply ? null : reads.topology(task.clusterId(), task.databaseNo());
        var targets = apply
                ? redis.scanShards(task.clusterId(), task.databaseNo()).stream()
                        .map(s -> new ReadOnlyScanPort.Shard(s.id(), "")).toList()
                : topology.shards();
        var run = repository.latestRun(id).orElse(null);
        if (run == null || (!command.resume() && !run.runNo().equals("CRUN-" + jobId))
                || run.status() != command.phase())
            run = save(new CleanupGovernanceRun(null, id,
                    "CRUN-" + jobId, command.phase(),
                    apply
                            ? redis.countKeys(task.clusterId(), task.databaseNo())
                            : reads.count(task.clusterId(), task.databaseNo(), topology),
                    0, 0, 0, 0, 0, Instant.now(), null, null));
        long scanned = run.scannedKeys(), candidates = run.candidateKeys(), deleted = run.deletedKeys(),
                skipped = run.skippedKeys(), failed = run.failedKeys();
        var probe = new io.github.susongyan.redisops.platform.domain.validation.ValidationTask(null, "cleanup-probe",
                null,
                task.clusterId(), task.clusterId(), task.databaseNo(), task.databaseNo(), ValidationStrictness.REPORT,
                "[]", "[]", "cleanup", ValidationSamplingMode.COUNT, 1, null, 0, 64L * 1024 * 1024, 1, 1024, 1,
                ValidationTaskStatus.CREATED, null, 0, Instant.now(), Instant.now());
        if (!apply) {
            Set<String> expected = new HashSet<>();
            for (var shard : targets)
                expected.add(topology.checkpointId(shard));
            var saved = repository.checkpoints(run.id());
            if (saved.isEmpty() && scanned == 0) {
                for (String target : expected)
                    repository.saveCheckpoint(new CleanupGovernanceCheckpoint(run.id(), target, "0", 0,
                            TtlGovernanceStatus.RUNNING, Instant.now()));
            } else if (!expected
                    .equals(new HashSet<>(saved.stream().map(CleanupGovernanceCheckpoint::shardId).toList())))
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
                    page = new ReadOnlyScanPort.Page(primaryPage.nextCursor(), primaryPage.keys().stream()
                            .map(RedisValidationPort.ValidationKey::bytes).toList());
                } else
                    page = reads.scan(task.clusterId(), task.databaseNo(), shard, cursor, 200);
                cursor = page.cursor();
                for (var key : page.keys()) {
                    if (scanned >= task.impactLimit())
                        break;
                    if (key == null)
                        continue;
                    String name = new String(key, StandardCharsets.UTF_8);
                    if (!matches(task.includePattern(), name))
                        continue;
                    scanned++;
                    boolean missing = apply
                            ? redis.inspect(task.clusterId(), task.databaseNo(), key, probe).isEmpty()
                            : "none".equals(reads.metadata(task.clusterId(), task.databaseNo(), shard, key).type());
                    if (missing) {
                        skipped++;
                        continue;
                    }
                    candidates++;
                    if (apply) {
                        if (redis.unlinkIfPresent(task.clusterId(), task.databaseNo(), key))
                            deleted++;
                        else
                            skipped++;
                    }
                }
                var savedCheckpoint = new CleanupGovernanceCheckpoint(run.id(), shardId, cursor, scanned,
                        "0".equals(cursor) ? TtlGovernanceStatus.COMPLETED : TtlGovernanceStatus.RUNNING,
                        Instant.now());
                if (!jobs.renew(jobId, lease, Duration.ofMinutes(10)))
                    return;
                run = service.saveProgress(new CleanupGovernanceRun(run.id(), run.taskId(), run.runNo(),
                        command.phase(),
                        run.plannedKeys(), scanned, candidates, deleted, skipped, failed, run.startedAt(), null, null),
                        savedCheckpoint);
                rateLimit(page.keys().size(), task.scanRatePerSecond(), command);
            } while (!"0".equals(cursor) && scanned < task.impactLimit());
        }
        task = service.get(id);
        if (!command.accepts(task.version(), task.status()) || !jobs.renew(jobId, lease, Duration.ofMinutes(10)))
            return;
        if (!apply && !topology.fingerprint().equals(reads.topology(task.clusterId(), task.databaseNo()).fingerprint()))
            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
        service.completeRun(new CleanupGovernanceRun(run.id(), run.taskId(), run.runNo(), TtlGovernanceStatus.COMPLETED,
                run.plannedKeys(), scanned, candidates, deleted, skipped, failed, run.startedAt(), Instant.now(),
                null), command.version(), apply);
    }
    private CleanupGovernanceRun save(CleanupGovernanceRun run) {
        return repository.saveRun(run);
    }
    private static boolean matches(String pattern, String key) {
        return "*".equals(pattern) || key.matches(pattern.replace(".", "\\.").replace("*", ".*"));
    }
    private void rateLimit(int count, int rate, GovernanceJobCommand command) {
        try {
            long remaining = Math.max(0, count * 1000L / Math.max(1, rate));
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
            throw new IllegalStateException("cleanup governance interrupted", e);
        }
    }
}
