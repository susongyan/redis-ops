package io.github.redisops.domain.alert;

import java.time.Instant;

public record NotificationDelivery(Long id, long channelId, long alertEventId, int attemptCount, Instant nextAttemptAt,
        String status, String lastError, Instant createdAt, Instant updatedAt) {
}
