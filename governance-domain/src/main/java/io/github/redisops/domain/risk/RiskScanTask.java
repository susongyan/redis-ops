package io.github.redisops.domain.risk;
import java.time.Instant;
public record RiskScanTask(Long id, String taskNo, long clusterId, int databaseNo, String includePattern,
        boolean checkLargeKey, boolean checkNoTtl,
        long largeKeyThresholdBytes, int scanRatePerSecond, int maxFindings, RiskScanStatus status, long version,
        Instant createdAt, Instant updatedAt) {
}
