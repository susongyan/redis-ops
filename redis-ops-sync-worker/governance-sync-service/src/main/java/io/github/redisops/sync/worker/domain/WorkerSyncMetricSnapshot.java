package io.github.redisops.sync.worker.domain;

import java.time.Instant;

/** Worker-owned synchronization metric observation. */
public record WorkerSyncMetricSnapshot(long taskId, String channelId, Long rpoSeconds, Long estimatedLagSeconds,
        long replicationOffsetGap, long spoolBytes, long sourceBytesPerSecond, long targetBytesPerSecond,
        Long catchUpEtaSeconds, String rpoMethod, String confidence, Instant capturedAt) {
}
