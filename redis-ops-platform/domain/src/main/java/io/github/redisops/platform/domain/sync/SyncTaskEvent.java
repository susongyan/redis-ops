package io.github.redisops.platform.domain.sync;
import java.time.Instant;
public record SyncTaskEvent(Long id, long taskId, SyncTaskStatus fromStatus, SyncTaskStatus toStatus,
        String operator, String message, Instant createdAt) {
}
