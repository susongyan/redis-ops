package io.github.redisops.domain.risk;
import java.time.Instant;
public record RiskFinding(Long id, long runId, String riskType, RiskLevel riskLevel, String keyName, String keyHash,
        String redisType, Long memoryBytes, Long elementCount, Long ttlSeconds, String nodeId, Instant createdAt) {
}
