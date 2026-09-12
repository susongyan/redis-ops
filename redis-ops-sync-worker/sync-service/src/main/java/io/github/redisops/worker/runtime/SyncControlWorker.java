package io.github.redisops.worker.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "sync.engine.enabled", havingValue = "true", matchIfMissing = true)
public class SyncControlWorker {
    private static final List<String> TYPES = List.of("SYNC_PRECHECK", "SYNC_START", "SYNC_PAUSE", "SYNC_RESUME",
            "SYNC_FINISH", "SYNC_CANCEL", "SYNC_RATE_LIMIT");
    private final WorkerControlJobPort jobs;
    private final NativeSyncCoordinator coordinator;
    private final String owner;
    public SyncControlWorker(WorkerControlJobPort jobs, NativeSyncCoordinator coordinator) {
        this.jobs = jobs;
        this.coordinator = coordinator;
        this.owner = coordinator.instanceId() + ":control:" + UUID.randomUUID();
    }
    @Scheduled(fixedDelayString = "${sync.engine.poll-interval-ms:500}")
    public void poll() {
        for (String type : TYPES)
            claim(type).ifPresent(this::execute);
    }
    private java.util.Optional<WorkerControlJob> claim(String type) {
        String claimOwner = owner + ":" + type;
        return switch (type) {
            case "SYNC_PRECHECK", "SYNC_START" ->
                jobs.claim(type, claimOwner, Duration.ofSeconds(30));
            case "SYNC_RESUME", "SYNC_CANCEL" ->
                jobs.claimForRuntime(type, claimOwner, coordinator.instanceId(), Duration.ofSeconds(30), true);
            default ->
                jobs.claimForRuntime(type, claimOwner, coordinator.instanceId(), Duration.ofSeconds(30), false);
        };
    }
    private void execute(WorkerControlJob job) {
        String lease = job.leaseOwner();
        try {
            switch (job.type()) {
                case "SYNC_PRECHECK" -> coordinator.precheck(job.taskId());
                case "SYNC_START" -> coordinator.start(job.taskId());
                case "SYNC_PAUSE" -> coordinator.pause(job.taskId());
                case "SYNC_RESUME" -> coordinator.resume(job.taskId());
                case "SYNC_FINISH" -> coordinator.finish(job.taskId());
                case "SYNC_CANCEL" -> coordinator.cancel(job.taskId());
                case "SYNC_RATE_LIMIT" -> coordinator.limits(job.taskId());
                default -> throw new IllegalArgumentException("unsupported sync control job: " + job.type());
            }
            jobs.complete(job.id(), lease);
        } catch (RuntimeException error) {
            jobs.retryOrFail(job.id(), lease, safe(error));
        }
    }
    private static String safe(Throwable error) {
        String x = error.getMessage();
        if (x == null)
            x = error.getClass().getSimpleName();
        return x.substring(0, Math.min(x.length(), 1000));
    }
}
