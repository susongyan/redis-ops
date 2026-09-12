package io.github.redisops.domain.sync;

import java.time.Instant;

public record SyncChannelCheckpoint(Long id, long taskId, String channelId, String sourceNodeId,
        String slotRanges, String replicationId, long receivedOffset, long appliedOffset,
        String status, Instant lastHeartbeatAt, Instant updatedAt) {
}
