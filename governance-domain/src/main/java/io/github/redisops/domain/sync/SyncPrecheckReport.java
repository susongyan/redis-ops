package io.github.redisops.domain.sync;

import java.time.Instant;

public record SyncPrecheckReport(Long id, long taskId, String status, String reportJson, Instant checkedAt,
        Instant validUntil) {
    public boolean validAt(Instant now) {
        return "PASSED".equals(status) && validUntil != null && validUntil.isAfter(now);
    }
}
