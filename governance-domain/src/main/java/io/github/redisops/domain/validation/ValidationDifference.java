package io.github.redisops.domain.validation;

import java.time.Instant;

public record ValidationDifference(Long id, long runId, ValidationDifferenceType differenceType, String keyHash,
        String keyName, String redisType, Long sourceSize, Long targetSize, Long sourceTtlSeconds,
        Long targetTtlSeconds,
        String comparisonLevel, String degradedReason, Instant createdAt) {
}
