package io.github.redisops.worker;

import io.github.redisops.application.asset.AssetService;
import io.github.redisops.domain.job.AsyncJob;
import io.github.redisops.domain.job.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class DiscoveryJobWorker {
    private static final Pattern CLUSTER_ID = Pattern.compile("\\\"clusterId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final AssetService assets;
    private final String instanceId;
    public DiscoveryJobWorker(JobRepository jobs, AssetService assets,
            @Value("${worker.instance-id:${HOSTNAME:local-worker}}") String instanceId) {
        this.jobs = jobs;
        this.assets = assets;
        this.instanceId = instanceId + "-" + UUID.randomUUID();
    }
    @Scheduled(fixedDelayString = "${worker.discovery.poll-interval-ms:1000}")
    public void poll() {
        String leaseOwner = instanceId + ":" + UUID.randomUUID();
        jobs.claimNext("CLUSTER_DISCOVERY", leaseOwner, Duration.ofSeconds(30))
                .ifPresent(job -> execute(job, leaseOwner));
    }
    private void execute(AsyncJob job, String leaseOwner) {
        try {
            long clusterId = parseClusterId(job.payload());
            assets.executeDiscovery(job.bizId(), clusterId, "system:" + instanceId);
            jobs.complete(job.id(), leaseOwner);
        } catch (RuntimeException error) {
            jobs.retryOrFail(job.id(), leaseOwner, abbreviate(error.getMessage()));
        }
    }
    private static long parseClusterId(String payload) {
        Matcher matcher = CLUSTER_ID.matcher(payload);
        if (!matcher.find())
            throw new IllegalArgumentException("clusterId missing in job payload");
        return Long.parseLong(matcher.group(1));
    }
    private static String abbreviate(String value) {
        if (value == null)
            return "unknown error";
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
