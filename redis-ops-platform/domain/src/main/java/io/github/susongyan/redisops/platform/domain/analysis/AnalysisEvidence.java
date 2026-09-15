package io.github.susongyan.redisops.platform.domain.analysis;

public record AnalysisEvidence(String reference, String kind, String summary) {
    public AnalysisEvidence {
        if (reference == null || reference.isBlank() || reference.length() > 512)
            throw new IllegalArgumentException("evidence reference is required");
        if (summary != null && summary.length() > 1000)
            throw new IllegalArgumentException("evidence summary is too long");
        if (kind != null && kind.length() > 64)
            throw new IllegalArgumentException("evidence kind is too long");
    }
}
