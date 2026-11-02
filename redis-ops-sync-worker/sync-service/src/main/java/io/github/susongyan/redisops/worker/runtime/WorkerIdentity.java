package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerMyBatisSyncRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stable worker identity used for task assignment and registry heartbeat. Unlike the
 * process-unique lease owner in {@link NativeSyncCoordinator}, the instance name here is
 * the raw {@code sync.engine.instance-id} value and survives restarts, so an assignment
 * recorded on a task still points at this worker after a redeploy. The HTTP port is
 * auto-derived from {@code server.port} so several local instances (distinct ports) need
 * no extra configuration to register distinctly.
 */
@Component
public class WorkerIdentity {
    private final String instanceName;
    private final String workerIp;
    private final int workerPort;

    public WorkerIdentity(@Value("${sync.engine.instance-id:${HOSTNAME:local-sync}}") String instanceId,
            @Value("${sync.engine.worker-ip:}") String workerIp,
            @Value("${server.port:8080}") int workerPort) {
        this.instanceName = instanceId == null || instanceId.isBlank() ? "local-sync" : instanceId.trim();
        this.workerIp = WorkerMyBatisSyncRepository.resolveIp(workerIp);
        this.workerPort = workerPort;
    }

    public String instanceName() {
        return instanceName;
    }

    public String workerIp() {
        return workerIp;
    }

    public int workerPort() {
        return workerPort;
    }
}
