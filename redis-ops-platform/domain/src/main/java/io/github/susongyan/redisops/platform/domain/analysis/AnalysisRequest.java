package io.github.susongyan.redisops.platform.domain.analysis;

import java.util.List;
import java.util.Map;

public record AnalysisRequest(AnalysisType type, String resourceType, String resourceId, Map<String, String> facts,
        List<AnalysisEvidence> evidence, List<String> incidentRefs) {
    public AnalysisRequest {
        if (type == null)
            throw new IllegalArgumentException("analysis type is required");
        if (facts != null && facts.size() > 100)
            throw new IllegalArgumentException("too many analysis facts");
        if (resourceType != null && !resourceType.matches("[A-Z_]{1,64}"))
            throw new IllegalArgumentException("invalid analysis resource type");
        if (resourceId != null && (resourceId.isBlank() || resourceId.length() > 128))
            throw new IllegalArgumentException("invalid analysis resource id");
        facts = facts == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(facts));
        for (var fact : facts.entrySet()) {
            if (fact.getKey() == null || !fact.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || fact.getValue() == null || fact.getValue().length() > 1000)
                throw new IllegalArgumentException("invalid analysis fact");
            if (fact.getKey().matches("(?i).*(password|secret|token|credential|ciphertext).*"))
                throw new IllegalArgumentException("secret fields cannot be analysis facts");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        incidentRefs = incidentRefs == null ? List.of() : List.copyOf(incidentRefs);
        if (evidence.size() > 100 || incidentRefs.size() > 20
                || incidentRefs.stream().anyMatch(ref -> ref.isBlank() || ref.length() > 256))
            throw new IllegalArgumentException("invalid analysis references");
    }
}
