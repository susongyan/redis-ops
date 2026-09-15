package io.github.susongyan.redisops.platform.domain.analysis;

public record AnalysisRecommendation(String action, String reason, boolean requiresApproval) {
    public AnalysisRecommendation {
        if (action == null || !action.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("invalid analysis recommendation action");
        if (reason != null && reason.length() > 2000)
            throw new IllegalArgumentException("analysis recommendation reason is too long");
        // Analysis is advisory only; provider output cannot authorize an operation.
        requiresApproval = true;
    }
}
