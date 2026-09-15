package io.github.susongyan.redisops.platform.domain.analysis;

import java.time.Instant;

public record AnalysisAgentProfile(Long id, String name, AnalysisProtocol protocol, String endpoint, boolean enabled,
        String supportedTypesJson, int timeoutMs, int priority, long version, Instant createdAt, Instant updatedAt) {
    public AnalysisAgentProfile {
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("agent name is required");
        if (protocol == null)
            throw new IllegalArgumentException("agent protocol is required");
        if (priority < 1 || priority > 1000)
            throw new IllegalArgumentException("agent priority must be between 1 and 1000");
        supportedTypesJson = supportedTypesJson == null ? "[]" : supportedTypesJson;
        String type = "\"(?:ALERT|SYNC|VALIDATION|RISK_SCAN|INCIDENT)\"";
        if (!supportedTypesJson.matches("\\[\\s*(?:" + type + "\\s*(?:,\\s*" + type + "\\s*)*)?\\]"))
            throw new IllegalArgumentException("agent supported types must be an array of known analysis types");
        if (timeoutMs < 100 || timeoutMs > 120_000)
            throw new IllegalArgumentException("timeout must be between 100 and 120000 milliseconds");
        if (endpoint != null && !endpoint.isBlank()) {
            try {
                var uri = java.net.URI.create(endpoint);
                if (endpoint.length() > 512 || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null
                        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "agent endpoint must be an HTTP(S) URL without credentials, query or fragment");
            }
        }
    }
}
