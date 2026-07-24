package io.github.redisops.sync.engine;

import io.github.redisops.domain.job.*;
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
    private final JobRepository jobs;
    private final NativeSyncCoordinator coordinator;
    private final String owner = "sync-control-" + UUID.randomUUID();
    public SyncControlWorker(JobRepository jobs, NativeSyncCoordinator coordinator) {
        this.jobs = jobs;
        this.coordinator = coordinator;
    }
    @Scheduled(fixedDelayString = "${sync.engine.poll-interval-ms:500}")
    public void poll() {
        for (String type : TYPES)
            jobs.claimNext(type, owner + ":" + type, Duration.ofSeconds(30)).ifPresent(this::execute);
    }
    private void execute(AsyncJob job) {
        String lease = job.leaseOwner();
        try {
            switch (job.jobType()) {
                case "SYNC_PRECHECK" -> coordinator.precheck(job.bizId());
                case "SYNC_START" -> coordinator.start(job.bizId());
                case "SYNC_PAUSE" -> coordinator.pause(job.bizId());
                case "SYNC_RESUME" -> coordinator.resume(job.bizId());
                case "SYNC_FINISH" -> coordinator.finish(job.bizId());
                case "SYNC_CANCEL" -> coordinator.cancel(job.bizId());
                case "SYNC_RATE_LIMIT" -> coordinator.limits(job.bizId());
                default -> throw new IllegalArgumentException("unsupported sync control job: " + job.jobType());
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
