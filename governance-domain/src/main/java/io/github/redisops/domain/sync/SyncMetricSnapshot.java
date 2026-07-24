package io.github.redisops.domain.sync;

import java.time.Instant;

public record SyncMetricSnapshot(Long id, long taskId, String channelId, Long timestampLagSeconds,
        Long estimatedLagSeconds, long offsetGapBytes, long backlogBytes,
        long sourceBytesPerSecond, long targetApplyBytesPerSecond, Long catchUpEtaSeconds,
        String calculationMethod, String confidence, Instant collectedAt) {
}
