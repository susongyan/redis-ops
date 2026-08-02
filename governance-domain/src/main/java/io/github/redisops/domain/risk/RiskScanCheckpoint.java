package io.github.redisops.domain.risk;

import java.time.Instant;

public record RiskScanCheckpoint(long runId, String shardId, String cursor, long scannedKeys, RiskScanStatus status,
        Instant updatedAt) {
}
