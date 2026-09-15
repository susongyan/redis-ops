package io.github.susongyan.redisops.platform.domain.distribution;

import java.util.*;

public record DistributionCheckpoint(String fingerprint, List<Cursor> cursors, int nextShard,
        long observed, long elapsedMillis, List<DistributionCounter.Entry> entries, int effectiveIntervalMillis,
        int slowStreak) {
    public record Cursor(DistributionScanPort.Shard shard, String cursor, boolean completed) {
    }
    public DistributionCheckpoint {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || cursors == null || cursors.isEmpty()
                || cursors.size() > 256 || nextShard < 0 || nextShard >= cursors.size() || observed < 0
                || elapsedMillis < 0
                || entries == null || entries.size() > 1006 || effectiveIntervalMillis < 10
                || effectiveIntervalMillis > 60000
                || slowStreak < 0 || slowStreak > 10)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CHECKPOINT");
        for (Cursor c : cursors)
            if (c == null || c.shard() == null || c.cursor() == null || !c.cursor().matches("[0-9]{1,20}"))
                throw new IllegalArgumentException("INVALID_DISTRIBUTION_CURSOR");
        cursors = List.copyOf(cursors);
        entries = List.copyOf(entries);
    }
}
