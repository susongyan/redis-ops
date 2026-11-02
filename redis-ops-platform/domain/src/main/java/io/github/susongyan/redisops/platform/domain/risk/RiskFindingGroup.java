package io.github.susongyan.redisops.platform.domain.risk;
/** Aggregated NO_TTL findings keyed by key pattern; hitCount is exact, sampleKey is the first hit. */
public record RiskFindingGroup(long runId, String riskType, String groupText, long hitCount, String sampleKey,
        Long maxMemoryBytes) {
    public static final String NO_TTL = "NO_TTL";
}
