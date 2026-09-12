package io.github.redisops.domain.validation;

import java.time.Instant;

public record ValidationRun(Long id, long taskId, String runNo, String status, long plannedKeys, long scannedKeys,
        long comparedKeys,
        long differenceCount, long degradedCount, long unverifiableCount, long inconclusiveCount, Instant startedAt,
        Instant finishedAt, String summaryJson) {
}
