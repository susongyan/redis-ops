package io.github.susongyan.redisops.platform.domain.sync;

import java.time.Instant;

/** Observational task ownership, not a machine health check. */
public record SyncWorkerAssignment(long taskId, String runtimeId, String leaseOwner, String workerIp,
        String phase, Instant heartbeatAt, Instant leaseUntil, String leaseStatus) {
}
