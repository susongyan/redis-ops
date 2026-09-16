package io.github.susongyan.redisops.platform.domain.sync;
import java.time.Instant;
public record SyncTaskEvent(Long id, long taskId, SyncTaskStatus fromStatus, SyncTaskStatus toStatus,
        String operator, String message, Instant createdAt, String operatorSnapshot) {
    public SyncTaskEvent(Long id, long taskId, SyncTaskStatus fromStatus, SyncTaskStatus toStatus,
            String operator, String message, Instant createdAt) {
        this(id, taskId, fromStatus, toStatus, operator, message, createdAt, null);
    }
}
