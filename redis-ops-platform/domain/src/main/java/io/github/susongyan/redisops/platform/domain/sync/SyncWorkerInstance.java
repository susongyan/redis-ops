package io.github.susongyan.redisops.platform.domain.sync;
import java.time.Instant;
/** Row of the worker heartbeat registry; {@code online} is computed by the service from heartbeat freshness. */
public record SyncWorkerInstance(String instanceName, String workerIp, Integer workerPort, String version,
        Instant startedAt, Instant heartbeatAt, boolean online) {
}
