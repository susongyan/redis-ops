package io.github.redisops.worker;

import io.github.redisops.application.validation.ValidationService;
import io.github.redisops.domain.job.*;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class ValidationJobWorker {
    private static final Pattern TASK_ID = Pattern.compile("\\\"validationTaskId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final ValidationService service;
    private final String instanceId;
    public ValidationJobWorker(JobRepository jobs, ValidationService service,
            @Value("${worker.instance-id:${HOSTNAME:local-worker}}") String instanceId) {
        this.jobs = jobs;
        this.service = service;
        this.instanceId = instanceId + "-" + UUID.randomUUID();
    }
    @Scheduled(fixedDelayString = "${worker.validation.poll-interval-ms:1000}")
    public void poll() {
        String owner = instanceId + ":" + UUID.randomUUID();
        jobs.claimNext("DATA_VALIDATION", owner, Duration.ofSeconds(300)).ifPresent(job -> execute(job, owner));
    }
    private void execute(AsyncJob job, String owner) {
        try {
            Matcher matcher = TASK_ID.matcher(job.payload());
            if (!matcher.find())
                throw new IllegalArgumentException("validationTaskId missing");
            service.execute(Long.parseLong(matcher.group(1)));
            jobs.complete(job.id(), owner);
        } catch (RuntimeException error) {
            jobs.retryOrFail(job.id(), owner, safe(error.getMessage()));
        }
    }
    private static String safe(String message) {
        return message == null ? "validation failed" : message.substring(0, Math.min(message.length(), 1000));
    }
}
