package io.github.redisops.platform.scheduling;

import io.github.redisops.platform.application.distribution.DistributionService;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class DistributionWorker {
    private final DistributionService service;
    public DistributionWorker(DistributionService service) {
        this.service = service;
    }
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        try {
            service.poll();
        } catch (RuntimeException ignored) {
            /* Retry database availability on next poll, without logging secrets. */}
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void cleanup() {
        try {
            service.cleanup();
        } catch (RuntimeException ignored) {
            /* bounded retry on next maintenance tick */}
    }
}
