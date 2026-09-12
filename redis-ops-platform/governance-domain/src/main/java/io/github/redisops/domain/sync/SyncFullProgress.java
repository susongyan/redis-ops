package io.github.redisops.domain.sync;

import java.time.Instant;

public record SyncFullProgress(Long id, long taskId, String fullSyncEpoch, String channelId, int lane, String stage,
        Long totalBytes, long receivedBytes, long parsedBytes, Long totalKeys, long parsedKeys,
        long appliedKeys, long appliedBytes, String status, Instant startedAt, Instant updatedAt) {
}
