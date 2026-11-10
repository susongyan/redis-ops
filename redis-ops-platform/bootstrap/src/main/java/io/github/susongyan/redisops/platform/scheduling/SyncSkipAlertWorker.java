package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.sync.SyncSkipAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically surfaces fresh big-key split skip events as LARGE_KEY_FOUND alerts. Idempotent per
 * open episode (see {@link SyncSkipAlertService}) and safe to run on every platform instance.
 */
@Component
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SyncSkipAlertWorker {
    private static final Logger log = LoggerFactory.getLogger(SyncSkipAlertWorker.class);
    private final SyncSkipAlertService skips;

    public SyncSkipAlertWorker(SyncSkipAlertService skips) {
        this.skips = skips;
    }

    @Scheduled(initialDelayString = "${sync.skip-alert.initial-delay-ms:90000}", fixedDelayString = "${sync.skip-alert.interval-ms:60000}")
    public void scan() {
        int raised = skips.alertRecentSkips();
        if (raised > 0)
            log.info("raised {} LARGE_KEY_FOUND alert(s) for sync tasks with fresh split skips", raised);
    }
}
