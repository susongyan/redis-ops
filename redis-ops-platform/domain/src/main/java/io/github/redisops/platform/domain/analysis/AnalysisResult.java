package io.github.redisops.platform.domain.analysis;

import java.time.Instant;
import java.util.List;

public record AnalysisResult(String requestId, AnalysisProtocol protocol, String provider, String status,
        String summary, double confidence, List<AnalysisEvidence> evidence,
        List<AnalysisRecommendation> recommendations,
        Instant createdAt) {
    public AnalysisResult {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || protocol == null
                || provider == null || provider.isBlank() || provider.length() > 128 || createdAt == null)
            throw new IllegalArgumentException("analysis result identity is required");
        if (!"COMPLETED".equals(status))
            throw new IllegalArgumentException("synchronous analysis requires a completed result");
        if (summary == null || summary.isBlank() || summary.length() > 2000)
            throw new IllegalArgumentException("analysis summary is invalid");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("analysis confidence must be within [0, 1]");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        if (evidence.size() > 100 || recommendations.size() > 20)
            throw new IllegalArgumentException("too many analysis result items");
    }
}
