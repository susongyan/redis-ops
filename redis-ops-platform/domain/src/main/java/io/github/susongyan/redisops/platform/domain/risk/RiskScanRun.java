package io.github.susongyan.redisops.platform.domain.risk;
import java.time.Instant;
public record RiskScanRun(Long id, long taskId, String runNo, RiskScanStatus status, long plannedKeys, long scannedKeys,
        long findingCount, Long noTtlTotal, Long largeKeyTotal, Instant startedAt, Instant completedAt,
        String errorCode) {
}
