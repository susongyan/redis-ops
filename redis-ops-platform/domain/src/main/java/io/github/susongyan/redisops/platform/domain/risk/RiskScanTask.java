package io.github.susongyan.redisops.platform.domain.risk;
import java.time.Instant;
public record RiskScanTask(Long id, String taskNo, long clusterId, int databaseNo, String includePattern,
        boolean checkLargeKey, boolean checkNoTtl,
        long largeKeyThresholdBytes, int scanRatePerSecond, int maxFindings, String groupDelimiter,
        int groupSegments, RiskScanStatus status, long version, Instant createdAt, Instant updatedAt) {
    public static RiskScanTask withStatus(RiskScanTask t, RiskScanStatus status, Instant now) {
        return new RiskScanTask(t.id(), t.taskNo(), t.clusterId(), t.databaseNo(), t.includePattern(),
                t.checkLargeKey(), t.checkNoTtl(), t.largeKeyThresholdBytes(), t.scanRatePerSecond(),
                t.maxFindings(), t.groupDelimiter(), t.groupSegments(), status, t.version(), t.createdAt(), now);
    }
}
