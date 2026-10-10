package io.github.susongyan.redisops.platform.domain.analysis;

import java.time.Instant;

public record AnalysisRun(Long id, AnalysisRequest request, AnalysisResult result, long version) {
    public AnalysisRun(Long id, AnalysisRequest request, AnalysisResult result) {
        this(id, request, result, 0);
    }
    public Instant createdAt() {
        return result.createdAt();
    }
}
