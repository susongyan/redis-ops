package io.github.redisops.platform.domain.analysis;

import java.time.Instant;

public record AnalysisRun(Long id, AnalysisRequest request, AnalysisResult result) {
    public Instant createdAt() {
        return result.createdAt();
    }
}
