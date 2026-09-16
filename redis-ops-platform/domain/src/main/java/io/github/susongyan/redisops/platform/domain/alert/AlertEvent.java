package io.github.susongyan.redisops.platform.domain.alert;
import java.time.Instant;
public record AlertEvent(Long id, long ruleId, String resourceType, String resourceId, AlertStatus status,
        AlertSeverity severity, String title, String evidenceJson, Instant firstSeenAt, Instant lastSeenAt,
        Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt, Instant silenceUntil, long version,
        String acknowledgedBySnapshot) {
    public AlertEvent(Long id, long ruleId, String resourceType, String resourceId, AlertStatus status,
            AlertSeverity severity, String title, String evidenceJson, Instant firstSeenAt, Instant lastSeenAt,
            Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt, Instant silenceUntil, long version) {
        this(id, ruleId, resourceType, resourceId, status, severity, title, evidenceJson, firstSeenAt, lastSeenAt,
                acknowledgedAt, acknowledgedBy, resolvedAt, silenceUntil, version, null);
    }
}
