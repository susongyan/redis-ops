package io.github.redisops.platform.domain.risk;
import java.time.Instant;
public record RiskScanRun(Long id, long taskId, String runNo, RiskScanStatus status, long plannedKeys, long scannedKeys,
        long findingCount, Instant startedAt, Instant completedAt, String errorCode) {
}
