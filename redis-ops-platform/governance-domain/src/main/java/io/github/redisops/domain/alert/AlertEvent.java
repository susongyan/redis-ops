package io.github.redisops.domain.alert;
import java.time.Instant;
public record AlertEvent(Long id, long ruleId, String resourceType, String resourceId, AlertStatus status,
        AlertSeverity severity, String title, String evidenceJson, Instant firstSeenAt, Instant lastSeenAt,
        Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt, Instant silenceUntil, long version) {
}
