package io.github.susongyan.redisops.worker.domain;

import java.time.Instant;

/** Worker-owned channel checkpoint observation. */
public record WorkerSyncChannelCheckpoint(long taskId, String channelId, String sourceNode, String slotRangesJson,
        String replicationId, long receivedOffset, long appliedOffset, String status, Instant startedAt,
        Instant updatedAt) {
}
