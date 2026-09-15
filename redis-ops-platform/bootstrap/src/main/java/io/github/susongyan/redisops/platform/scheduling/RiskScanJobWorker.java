package io.github.susongyan.redisops.platform.scheduling;
import io.github.susongyan.redisops.platform.application.risk.RiskScanService;
import io.github.susongyan.redisops.platform.domain.job.*;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RiskScanJobWorker {
    private static final Pattern ID = Pattern.compile("\\\"scanTaskId\\\"\\s*:\\s*(\\d+)");
    private final JobRepository jobs;
    private final RiskScanService scans;
    private final String owner;
    public RiskScanJobWorker(JobRepository j, RiskScanService s,
            @Value("${platform.jobs.instance-id:${HOSTNAME:local-platform}}") String i) {
        jobs = j;
        scans = s;
        owner = i + "-risk-" + UUID.randomUUID();
    }
    @Scheduled(fixedDelayString = "${platform.jobs.risk-scan.poll-interval-ms:1000}")
    public void poll() {
        String lease = owner + ":" + UUID.randomUUID();
        jobs.claimNext("RISK_SCAN", lease, Duration.ofMinutes(10)).ifPresent(j -> {
            try {
                Matcher m = ID.matcher(j.payload());
                if (!m.find())
                    throw new IllegalArgumentException("scanTaskId missing");
                scans.execute(Long.parseLong(m.group(1)));
                jobs.complete(j.id(), lease);
            } catch (RuntimeException e) {
                jobs.retryOrFail(j.id(), lease,
                        e.getMessage() == null
                                ? "risk scan failed"
                                : e.getMessage().substring(0, Math.min(1000, e.getMessage().length())));
            }
        });
    }
}
