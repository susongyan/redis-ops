package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sync.engine.enabled", havingValue = "true", matchIfMissing = true)
public class NativeSyncRecoveryWorker {
    private final SyncRepository sync;
    private final NativeSyncCoordinator coordinator;

    public NativeSyncRecoveryWorker(SyncRepository sync, NativeSyncCoordinator coordinator) {
        this.sync = sync;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${sync.engine.recovery-scan-interval-ms:2000}")
    public void recoverExpiredRuntimes() {
        for (var task : sync.findExpiredRecoverableTasks(10)) {
            try {
                coordinator.recover(task);
            } catch (IllegalStateException ignored) {
                // Another worker may have won the atomic MySQL claim.
            }
        }
    }
}
