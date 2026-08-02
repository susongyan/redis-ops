package io.github.redisops.domain.alert;

import java.time.Instant;
import java.util.UUID;

public record NotificationChannel(Long id, UUID channelUuid, String name, String type, String status,
        boolean configured, long version, Instant createdAt, Instant updatedAt) {
}
