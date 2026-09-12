package io.github.redisops.domain.validation;

import java.time.Instant;

public record ValidationTask(Long id, String taskNo, Long syncTaskId, long sourceClusterId, long targetClusterId,
        int sourceDb, int targetDb, ValidationStrictness strictness, String includePatternsJson,
        String excludePatternsJson, String sampleSeed, ValidationSamplingMode samplingMode, int sampleLimit,
        Double samplePercentage, long ttlToleranceSeconds,
        long largeKeyThresholdBytes, long maxDeepCompareBytes, int chunkBytes, int maxElementsPerKey,
        ValidationTaskStatus status, String lastError, long version, Instant createdAt, Instant updatedAt) {
    public static final long DEFAULT_LARGE_KEY_THRESHOLD_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_MAX_DEEP_COMPARE_BYTES = 8L * 1024 * 1024;
    public static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_ELEMENTS_PER_KEY = 100_000;
    public static final int DEFAULT_SAMPLE_LIMIT = 1_000;
    public static final long DEFAULT_TTL_TOLERANCE_SECONDS = 5;
}
