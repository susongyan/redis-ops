package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.sync.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drops sync_worker_registry rows whose heartbeat is older than
 * {@code sync.worker.registry-retention-seconds} (default 30m) so dead instances do not pile up as
 * permanent "offline" entries. Idempotent and safe to run on every platform instance.
 */
@Component
@ConditionalOnProperty(name = "platform.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SyncWorkerRegistryJanitor {
    private static final Logger log = LoggerFactory.getLogger(SyncWorkerRegistryJanitor.class);
    private final SyncService syncs;

    public SyncWorkerRegistryJanitor(SyncService syncs) {
        this.syncs = syncs;
    }

    @Scheduled(initialDelayString = "${sync.worker.registry-purge-initial-delay-ms:120000}", fixedDelayString = "${sync.worker.registry-purge-interval-ms:600000}")
    public void purge() {
        int purged = syncs.purgeOfflineWorkers();
        if (purged > 0)
            log.info("purged {} stale sync worker registry row(s) beyond the retention window", purged);
    }
}
