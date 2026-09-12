package io.github.redisops.domain.alert;
import java.time.Instant;
public record AlertRule(Long id, String name, String ruleType, AlertSeverity severity, boolean enabled,
        Double thresholdValue, int durationSeconds, Long channelId, long version, Instant createdAt,
        Instant updatedAt) {
}
