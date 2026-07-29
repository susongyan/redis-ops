package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncFullProgress;
import io.github.redisops.domain.sync.SyncRepository;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;

final class FullSyncProgressTracker {
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final long taskId;
    private final String epoch;
    private final String channel;
    private final SyncRepository repository;
    private final Instant startedAt = Instant.now();
    private final AtomicLong receivedBytes = new AtomicLong();
    private final AtomicLong parsedBytes = new AtomicLong();
    private final AtomicLong parsedKeys = new AtomicLong();
    private final AtomicLongArray laneKeys;
    private final AtomicLongArray laneBytes;
    private final AtomicLong lastAggregateReport = new AtomicLong();
    private final AtomicLongArray lastLaneReport;
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile Long totalBytes;
    private volatile Long totalKeys;
    private volatile String stage = "RECEIVING_RDB";
    private volatile String status = "RUNNING";

    FullSyncProgressTracker(long taskId, String epoch, String channel, int lanes, SyncRepository repository) {
        this.taskId = taskId;
        this.epoch = epoch;
        this.channel = channel;
        this.repository = repository;
        this.laneKeys = new AtomicLongArray(lanes);
        this.laneBytes = new AtomicLongArray(lanes);
        this.lastLaneReport = new AtomicLongArray(lanes);
    }

    void startReceiving(long expectedBytes) {
        totalBytes = expectedBytes >= 0 ? expectedBytes : null;
        stage = "RECEIVING_RDB";
        reportAggregate(true);
    }

    void received(long bytes) {
        receivedBytes.accumulateAndGet(bytes, Math::max);
        reportAggregate(false);
    }

    void rdbReceived(long bytes) {
        receivedBytes.accumulateAndGet(bytes, Math::max);
        if (totalBytes == null)
            totalBytes = bytes;
        stage = "PARSING_RDB";
        reportAggregate(true);
    }

    void parsed(long bytes, boolean accepted) {
        parsedBytes.accumulateAndGet(bytes, Math::max);
        if (accepted)
            parsedKeys.incrementAndGet();
        stage = "RESTORING";
        reportAggregate(false);
    }

    void parsingComplete(long bytes) {
        parsedBytes.accumulateAndGet(bytes, Math::max);
        totalKeys = parsedKeys.get();
        stage = "RESTORING";
        reportAggregate(true);
    }

    void applied(int lane, long keys, long bytes) {
        laneKeys.addAndGet(lane, keys);
        laneBytes.addAndGet(lane, bytes);
        reportLane(lane, false);
        reportAggregate(false);
    }

    void completed() {
        completed.set(true);
        totalKeys = parsedKeys.get();
        stage = "COMPLETED";
        status = "COMPLETED";
        for (int lane = 0; lane < laneKeys.length(); lane++)
            reportLane(lane, true);
        reportAggregate(true);
    }

    void failed() {
        if (completed.get())
            return;
        status = "FAILED";
        for (int lane = 0; lane < laneKeys.length(); lane++)
            reportLane(lane, true);
        reportAggregate(true);
    }

    private void reportAggregate(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastAggregateReport.get() < REPORT_INTERVAL_NANOS)
            return;
        lastAggregateReport.set(now);
        long appliedKeys = 0;
        long appliedBytes = 0;
        for (int lane = 0; lane < laneKeys.length(); lane++) {
            appliedKeys += laneKeys.get(lane);
            appliedBytes += laneBytes.get(lane);
        }
        persist(row(-1, appliedKeys, appliedBytes));
    }

    private void reportLane(int lane, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastLaneReport.get(lane) < REPORT_INTERVAL_NANOS)
            return;
        lastLaneReport.set(lane, now);
        persist(row(lane, laneKeys.get(lane), laneBytes.get(lane)));
    }

    private SyncFullProgress row(int lane, long appliedKeys, long appliedBytes) {
        return new SyncFullProgress(null, taskId, epoch, channel, lane, stage, totalBytes,
                receivedBytes.get(), parsedBytes.get(), totalKeys, parsedKeys.get(), appliedKeys,
                appliedBytes, status, startedAt, Instant.now());
    }

    private void persist(SyncFullProgress progress) {
        try {
            repository.upsertFullProgress(progress);
        } catch (RuntimeException ignored) {
            // Progress is observational. Lease renewal remains the fail-closed control for MySQL outages.
        }
    }
}
