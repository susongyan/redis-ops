package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerRegistryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes this worker's liveness to the registry consumed by the platform assignment UI
 * and by other workers' claim escape hatch. A failed upsert (missing table, missing grants)
 * only degrades to "worker shows offline / tasks stay auto-claimable" — it must never take
 * the worker down.
 */
@Component
@ConditionalOnProperty(name = "sync.engine.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);
    private final WorkerRegistryMapper registry;
    private final WorkerIdentity identity;
    private final String version;
    private final Instant startedAt = Instant.now();

    public WorkerHeartbeat(WorkerRegistryMapper registry, WorkerIdentity identity,
            ObjectProvider<org.springframework.boot.info.BuildProperties> build) {
        this.registry = registry;
        this.identity = identity;
        var properties = build.getIfAvailable();
        this.version = properties == null ? "unknown" : properties.getVersion();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        beat();
    }

    @Scheduled(fixedDelayString = "${sync.engine.heartbeat-interval-ms:15000}")
    public void beat() {
        try {
            registry.upsert(identity.instanceName(), identity.workerIp(), identity.workerPort(), version,
                    startedAt);
        } catch (RuntimeException error) {
            log.warn("worker heartbeat upsert failed: {}", error.getMessage());
        }
    }
}
