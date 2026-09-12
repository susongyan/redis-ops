package io.github.redisops.worker.domain;

import java.time.Instant;

/** Worker-side view of a persisted precheck result. */
public record WorkerSyncPrecheckReport(long taskId, String status, String reportJson, Instant checkedAt,
        Instant validUntil) {
    public boolean validAt(Instant now) {
        return "PASSED".equals(status) && validUntil != null && validUntil.isAfter(now);
    }
}
