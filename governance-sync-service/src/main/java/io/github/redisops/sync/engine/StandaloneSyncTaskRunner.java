package io.github.redisops.sync.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.sync.*;
import io.github.redisops.sync.protocol.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class StandaloneSyncTaskRunner implements SyncTaskRunner {
    private static final String CHANNEL = "standalone";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SyncTask originalTask;
    private final boolean recovery;
    private final RedisConnectionProfileProvider profiles;
    private final SyncRepository sync;
    private final SyncRunnerStateReporter reporter;
    private final SpoolKeyProvider spoolKeys;
    private final ObjectMapper json;
    private final Path dataDirectory;
    private final long segmentBytes;
    private final Duration connectTimeout;
    private final int fullApplyConcurrency;
    private final int fullApplyQueueCapacity;
    private final int fullApplyPipelineSize;
    private final long fullApplyTransactionMaxBytes;
    private final LeaseGuard leaseGuard;
    private final long metricIntervalNanos;
    private final ExecutorService workers;
    private final BlockingQueue<ReplicationCommand> applyQueue = new LinkedBlockingQueue<>(10_000);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stoppedByFailure = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger activeApplies = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong receivedOffset = new AtomicLong(-1);
    private final AtomicLong appliedOffset = new AtomicLong(-1);
    private final Object applyGate = new Object();

    private volatile SyncTask limits;
    private volatile String phase = "CREATED";
    private volatile boolean finishing;
    private volatile long generation;
    private volatile TargetFence targetFence;
    private volatile Optional<TargetCheckpoint> checkpointAtFence = Optional.empty();
    private volatile int sourceDatabase;
    private volatile String replicationId;
    private volatile Future<?> pipeline;
    private volatile Future<?> reader;
    private volatile String lastFailure;
    private volatile boolean readerStarted;
    private volatile boolean spoolFallback;
    private volatile SourceReplicationSession source;
    private RedisConnectionProfile sourceProfile;
    private RedisConnectionProfile targetProfile;
    private TargetCommandSession target;
    private TargetCommandSession heartbeatSource;
    private EncryptedSpool spool;
    private CommandPlanner planner;
    private KeyFilter filter;
    private long lastSummaryNanos;
    private long lastMetricNanos;
    private long lastPruneNanos;
    private long lastApplyNanos;
    private long lastMetricReceived;
    private long lastMetricApplied;
    private Instant lastMetricAt = Instant.now();
    private long lastHeartbeatWriteNanos;
    private boolean caughtUp;
    private volatile long lastAppliedHeartbeatMillis;
    private final byte[] heartbeatKey;

    StandaloneSyncTaskRunner(SyncTask task, boolean recovery, RedisConnectionProfileProvider profiles,
            SyncRepository sync, SyncRunnerStateReporter reporter, SpoolKeyProvider spoolKeys, ObjectMapper json,
            Path dataDirectory, long segmentBytes, Duration connectTimeout, int fullApplyConcurrency,
            int fullApplyQueueCapacity, int fullApplyPipelineSize, long fullApplyTransactionMaxBytes,
            Duration leaseSafetyMargin, Duration metricInterval) {
        this.originalTask = task;
        this.recovery = recovery;
        this.profiles = profiles;
        this.sync = sync;
        this.reporter = reporter;
        this.spoolKeys = spoolKeys;
        this.json = json;
        this.dataDirectory = dataDirectory;
        this.segmentBytes = segmentBytes;
        this.connectTimeout = connectTimeout;
        this.fullApplyConcurrency = fullApplyConcurrency;
        this.fullApplyQueueCapacity = fullApplyQueueCapacity;
        this.fullApplyPipelineSize = fullApplyPipelineSize;
        this.fullApplyTransactionMaxBytes = fullApplyTransactionMaxBytes;
        this.leaseGuard = new LeaseGuard(leaseSafetyMargin);
        if (metricInterval.isZero() || metricInterval.isNegative())
            throw new IllegalArgumentException("metric interval must be positive");
        this.metricIntervalNanos = metricInterval.toNanos();
        this.limits = task;
        this.heartbeatKey = ("__redis_ops_sync_hb__:" + task.id() + ":standalone")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        this.workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "redis-sync-" + task.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void prepare() {
        if (originalTask.id() == null)
            throw new IllegalArgumentException("sync task must be persisted");
        if (originalTask.fullSyncEpoch() == null || originalTask.fullSyncEpoch().isBlank())
            throw new IllegalStateException("sync task has no full sync epoch");
        try {
            sourceProfile = profiles.get(originalTask.sourceClusterId());
            targetProfile = profiles.get(originalTask.targetClusterId());
            if (sourceProfile.mode() != ClusterMode.STANDALONE || targetProfile.mode() != ClusterMode.STANDALONE)
                throw new SyncBlockedException("BLOCKED_UNSUPPORTED_TOPOLOGY",
                        "this runner currently supports Standalone to Standalone only");
            filter = new KeyFilter(patterns(originalTask.includePatternsJson()),
                    patterns(originalTask.excludePatternsJson()));
            planner = new CommandPlanner(filter, false, heartbeatKey);
            prepareSpool();
            target = new TargetCommandSession(targetProfile, originalTask.targetDb(), originalTask.id(),
                    connectTimeout);
            heartbeatSource = new TargetCommandSession(sourceProfile, originalTask.sourceDb(), originalTask.id(),
                    connectTimeout);
            target.assertReservedNamespaceAvailable();
            Optional<TargetCheckpoint> checkpoint = target.checkpoint();
            if (checkpoint.isPresent()) {
                TargetCheckpoint value = checkpoint.get();
                if (!value.epoch().equals(originalTask.fullSyncEpoch()))
                    throw new SyncBlockedException("BLOCKED_RESERVED_NAMESPACE",
                            "target checkpoint belongs to another full sync epoch");
                replicationId = value.replicationId();
                appliedOffset.set(value.appliedOffset());
                receivedOffset.set(value.appliedOffset());
                sourceDatabase = value.sourceDatabase();
            }
            phase = "PREPARED";
        } catch (IOException error) {
            throw new UncheckedIOException("cannot prepare standalone sync runner", error);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    @Override
    public void leaseAcquired(SyncRuntime runtime) {
        generation = runtime.fencingGeneration();
        Duration remaining = Duration.between(Instant.now(), runtime.leaseUntil());
        leaseGuard.grant(remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining);
        targetFence = new TargetFence(originalTask.fullSyncEpoch(), generation, runtime.runtimeId(),
                runtime.leaseOwner(), Instant.now());
        if (recovery)
            publishTargetFence("RECOVERING_PSYNC");
    }

    @Override
    public void leaseRenewed(Duration duration) {
        leaseGuard.renew(duration);
    }

    @Override
    public void revokeLease() {
        leaseGuard.revoke();
    }

    @Override
    public void assertLeaseValid() {
        leaseGuard.assertValid();
    }

    @Override
    public void start() {
        requirePrepared();
        if (recovery)
            throw new IllegalStateException("a recovery runner must be resumed");
        try {
            publishTargetFence("FULL_SYNCING");
            source = new SourceReplicationSession(sourceProfile, connectTimeout);
            ReplicationReply reply = source.start(sourceProfile, null, -1);
            if (!(reply instanceof ReplicationReply.FullResync full))
                throw new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                        "new sync task did not receive FULLRESYNC");
            replicationId = full.replicationId();
            receivedOffset.set(full.offset());
            appliedOffset.set(full.offset());
            phase = "FULL_SYNCING";
            pipeline = workers.submit(() -> runFullPipeline(full));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot start source replication", error);
        }
    }

    @Override
    public void pause() {
        synchronized (applyGate) {
            paused.set(true);
        }
        phase = "PAUSING";
        waitForApplyToStop();
        phase = "PAUSED";
    }

    @Override
    public void resume() {
        requirePrepared();
        paused.set(false);
        if (pipeline == null || pipeline.isDone()) {
            phase = "RESUMING";
            pipeline = workers.submit(this::runRecoveryPipeline);
        }
    }

    @Override
    public void finish() {
        finishing = true;
        SourceReplicationSession current = source;
        if (current != null) {
            try {
                current.readTimeout(Duration.ofSeconds(1));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        waitForPipeline(Duration.ofSeconds(60));
        if (appliedOffset.get() < receivedOffset.get())
            throw new IllegalStateException("target did not reach the final received offset");
        phase = "FINISHED";
    }

    @Override
    public void cancel() {
        leaseGuard.revoke();
        cancelled.set(true);
        paused.set(false);
        closeSource();
        workers.shutdownNow();
        phase = "CANCELLED";
    }

    @Override
    public void updateLimits(SyncTask task) {
        limits = task;
    }

    @Override
    public String phase() {
        return phase;
    }

    @Override
    public long spoolBytes() {
        return spool == null ? 0 : spool.bytes();
    }

    @Override
    public void close() {
        leaseGuard.revoke();
        cancelled.set(true);
        closeSource();
        workers.shutdownNow();
        closeQuietly(target);
        closeQuietly(heartbeatSource);
        closeQuietly(spool);
        closeQuietly(sourceProfile);
        closeQuietly(targetProfile);
    }

    private void runFullPipeline(ReplicationReply.FullResync full) {
        try {
            spoolRdb(source, full);
        } catch (Exception error) {
            fail(error);
        }
    }

    private void runRecoveryPipeline() {
        try {
            leaseGuard.assertValid();
            Optional<TargetCheckpoint> existing = checkpointAtFence;
            if (existing.isPresent()) {
                reportRecovery("RECOVERING_SPOOL", "RECOVERY_FROM_SPOOL");
                TargetCheckpoint checkpoint = existing.get();
                replicationId = checkpoint.replicationId();
                appliedOffset.set(checkpoint.appliedOffset());
                receivedOffset.accumulateAndGet(checkpoint.appliedOffset(), Math::max);
                sourceDatabase = checkpoint.sourceDatabase();
                TargetCheckpoint adopted = target.apply(List.of(),
                        new TargetCheckpoint(originalTask.fullSyncEpoch(), generation, replicationId,
                                checkpoint.appliedOffset(), sourceDatabase, Instant.now()),
                        targetFence, leaseGuard);
                appliedOffset.set(adopted.appliedOffset());
                receivedOffset.accumulateAndGet(adopted.appliedOffset(), Math::max);
                replaySpool();
                reportRecovery("RECOVERING_PSYNC", "RECOVERY_FROM_PSYNC");
                connectPartial();
                applyLive();
                return;
            }
            EncryptedSpool.FullMetadata metadata = spool.fullMetadata()
                    .orElseThrow(() -> new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                            "full sync spool metadata is missing"));
            replicationId = metadata.replicationId();
            reportRecovery("RECOVERING_SPOOL", "RECOVERY_FROM_SPOOL");
            applyRdb(metadata.baseOffset());
            replaySpool();
            reportRecovery("RECOVERING_PSYNC", "RECOVERY_FROM_PSYNC");
            connectPartial();
            applyLive();
        } catch (Exception error) {
            fail(error);
        }
    }

    private void spoolRdb(SourceReplicationSession session, ReplicationReply.FullResync full) throws Exception {
        leaseGuard.assertValid();
        session.spoolRdb(full, spool);
        leaseGuard.assertValid();
        spool.saveFullMetadata(full.replicationId(), full.offset());
        leaseGuard.assertValid();
        session.acknowledge(full.offset());
        session.readTimeout(Duration.ofSeconds(1));
        reader = workers.submit(this::readLoop);
        applyRdb(full.offset());
        replaySpool();
        applyLive();
    }

    private void applyRdb(long baseOffset) throws IOException {
        phase = "FULL_SYNCING";
        int restoreConcurrency = taskFullApplyConcurrency();
        int restoreQueueCapacity = Math.max(fullApplyQueueCapacity, restoreConcurrency);
        try (var input = spool.openRdb()) {
            try (var restores = new FullRestorePool(targetProfile, originalTask.targetDb(), originalTask.id(),
                    connectTimeout, restoreConcurrency, restoreQueueCapacity, taskFullApplyPipelineSize(),
                    fullApplyTransactionMaxBytes, targetFence, leaseGuard,
                    this::waitForApplyPermission, this::targetApplyFinished)) {
                try {
                    new RdbStreamParser(input).parse(event -> applyRdbEvent(event, restores));
                    restores.awaitCompletion();
                } catch (UncheckedIOException error) {
                    throw error.getCause();
                }
            } catch (UncheckedIOException error) {
                throw error.getCause();
            }
        }
        sourceDatabase = 0;
        TargetCheckpoint checkpoint = target.apply(List.of(),
                new TargetCheckpoint(originalTask.fullSyncEpoch(), generation, replicationId,
                        baseOffset, sourceDatabase, Instant.now()),
                targetFence, leaseGuard);
        appliedOffset.set(checkpoint.appliedOffset());
        spool.discardFullRdb();
        updateChannel("INCR_SYNCING");
        phase = "INCR_SYNCING";
        reporter.transition(originalTask.id(), SyncTaskStatus.INCR_SYNCING, null, null, null,
                "full RDB applied; incremental replication started");
    }

    private void applyRdbEvent(RdbEvent event, FullRestorePool restores) {
        try {
            if (event instanceof RdbEvent.KeyValue keyValue) {
                if (keyValue.database() == originalTask.sourceDb() && filter.accepts(keyValue.key()))
                    restores.submit(new TargetCommandSession.RestoreRequest(
                            keyValue.key(), keyValue.absoluteExpireMillis(), keyValue.dumpPayload()));
            } else if (event instanceof RdbEvent.FunctionLibrary function) {
                waitForApplyPermission();
                try {
                    target.loadFunction(function.payload(), targetFence, leaseGuard);
                } finally {
                    targetApplyFinished();
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void readLoop() {
        readerStarted = true;
        while (!cancelled.get() && !stoppedByFailure.get()) {
            try {
                if (finishing && source == null)
                    return;
                SourceReplicationSession currentSource = source;
                if (currentSource == null)
                    return;
                leaseGuard.assertValid();
                ReplicationCommand command = currentSource.readCommand();
                leaseGuard.assertValid();
                spool.append(command);
                receivedOffset.set(command.endOffset());
                leaseGuard.assertValid();
                currentSource.acknowledge(command.endOffset());
                applyQueue.put(command);
                maybeWriteHeartbeat();
                while (!cancelled.get() && spool.bytes() >= limits.spoolLimitBytes() * 9 / 10)
                    Thread.sleep(100);
                updateChannelThrottled("RUNNING");
            } catch (SocketTimeoutException timeout) {
                if (finishing)
                    return;
                try {
                    maybeWriteHeartbeat();
                } catch (IOException heartbeatFailure) {
                    fail(heartbeatFailure);
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                if (cancelled.get() || stoppedByFailure.get())
                    return;
                if (finishing)
                    return;
                lastFailure = "source read interrupted: " + safe(error);
                try {
                    reconnect();
                } catch (Exception reconnectFailure) {
                    fail(reconnectFailure);
                    return;
                }
            }
        }
    }

    private void replaySpool() throws IOException {
        leaseGuard.assertValid();
        List<ReplicationCommand> commands = spool.commandsAfter(appliedOffset.get());
        for (int start = 0; start < commands.size(); start += 100)
            applyBatchWhenAllowed(commands.subList(start, Math.min(start + 100, commands.size())));
    }

    private void applyLive() throws Exception {
        while (!cancelled.get() && !stoppedByFailure.get()) {
            if (paused.get()) {
                Thread.sleep(50);
                continue;
            }
            ReplicationCommand command = applyQueue.poll(250, TimeUnit.MILLISECONDS);
            if (command != null && command.endOffset() > appliedOffset.get()) {
                var batch = new java.util.ArrayList<ReplicationCommand>(100);
                batch.add(command);
                applyQueue.drainTo(batch, 99);
                applyBatchWhenAllowed(batch);
            }
            if (finishing && (reader == null || reader.isDone()) && applyQueue.isEmpty()
                    && appliedOffset.get() >= receivedOffset.get())
                return;
            markCaughtUpIfNeeded();
            saveMetricThrottled();
        }
    }

    private void applyBatchWhenAllowed(List<ReplicationCommand> commands) throws IOException {
        waitForApplyPermission();
        try {
            applyBatch(commands);
        } finally {
            targetApplyFinished();
        }
    }

    private void applyBatch(List<ReplicationCommand> commands) throws IOException {
        var planned = new java.util.ArrayList<CommandPlan.PlannedCommand>();
        ReplicationCommand last = null;
        long appliedHeartbeat = 0;
        for (ReplicationCommand command : commands) {
            if (command.endOffset() <= appliedOffset.get())
                continue;
            CommandPlan plan;
            if ("SELECT".equals(command.name())) {
                sourceDatabase = selectedDatabase(command);
                plan = CommandPlan.skip();
            } else if (sourceDatabase == originalTask.sourceDb()) {
                plan = planner.plan(command);
            } else {
                plan = CommandPlan.skip();
            }
            if (plan.disposition() == CommandPlan.Disposition.BLOCK)
                throw new SyncBlockedException("BLOCKED_UNSUPPORTED_COMMAND",
                        "command " + command.name() + " at offset " + command.endOffset() + " cannot be applied");
            planned.addAll(plan.commands());
            appliedHeartbeat = Math.max(appliedHeartbeat, heartbeatTimestamp(command));
            throttle(command);
            last = command;
        }
        if (last == null)
            return;
        TargetCheckpoint checkpoint = new TargetCheckpoint(originalTask.fullSyncEpoch(), generation,
                replicationId, last.endOffset(), sourceDatabase, Instant.now());
        TargetCheckpoint committed = target.apply(planned, checkpoint, targetFence, leaseGuard);
        appliedOffset.set(committed.appliedOffset());
        if (appliedHeartbeat > 0)
            lastAppliedHeartbeatMillis = appliedHeartbeat;
        if (shouldLeaveCaughtUp(caughtUp, appliedOffset.get(), receivedOffset.get(), applyQueue.isEmpty())) {
            caughtUp = false;
            phase = "INCR_SYNCING";
            reporter.transition(originalTask.id(), SyncTaskStatus.INCR_SYNCING, null, null, null,
                    "incremental backlog detected");
        }
        updateChannelThrottled("RUNNING");
        saveMetricThrottled();
        pruneSpoolThrottled();
    }

    private void connectPartial() throws IOException {
        leaseGuard.assertValid();
        closeSource();
        source = new SourceReplicationSession(sourceProfile, connectTimeout);
        long offset = appliedOffset.get();
        ReplicationReply reply = source.start(sourceProfile, replicationId, offset);
        if (!(reply instanceof ReplicationReply.Continue continued))
            throw new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                    "source backlog no longer contains the requested offset");
        if (continued.replicationId() != null)
            replicationId = continued.replicationId();
        source.continueCommands(offset);
        source.readTimeout(Duration.ofSeconds(1));
        reader = workers.submit(this::readLoop);
    }

    private void reconnect() throws IOException {
        leaseGuard.assertValid();
        long offset = receivedOffset.get();
        closeSource();
        source = new SourceReplicationSession(sourceProfile, connectTimeout);
        ReplicationReply reply = source.start(sourceProfile, replicationId, offset);
        if (!(reply instanceof ReplicationReply.Continue continued))
            throw new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                    "source backlog cannot continue after disconnect");
        if (continued.replicationId() != null)
            replicationId = continued.replicationId();
        source.continueCommands(offset);
        source.readTimeout(Duration.ofSeconds(1));
    }

    private void markCaughtUpIfNeeded() {
        if (!stoppedByFailure.get() && !caughtUp && appliedOffset.get() == receivedOffset.get()
                && applyQueue.isEmpty()) {
            caughtUp = true;
            phase = "CAUGHT_UP";
            reporter.transition(originalTask.id(), SyncTaskStatus.CAUGHT_UP, 0L, null, null,
                    "target checkpoint caught up with received offset");
            saveMetric(true);
        }
    }

    private void throttle(ReplicationCommand command) {
        long ops = limits.rateLimitOps();
        long bytes = Math.max(1, command.endOffset() - command.startOffset() + 1);
        long byOps = ops <= 0 ? 0 : 1_000_000_000L / ops;
        long bandwidth = limits.bandwidthLimitBytesPerSecond();
        long byBytes = bandwidth <= 0 ? 0 : Math.multiplyExact(1_000_000_000L, bytes) / bandwidth;
        long interval = Math.max(byOps, byBytes);
        long now = System.nanoTime();
        long wait = lastApplyNanos + interval - now;
        if (wait > 0)
            LockSupport.parkNanos(wait);
        lastApplyNanos = System.nanoTime();
    }

    private void updateChannelThrottled(String status) {
        long now = System.nanoTime();
        if (now - lastSummaryNanos < TimeUnit.SECONDS.toNanos(1))
            return;
        lastSummaryNanos = now;
        updateChannel(status);
    }

    private void updateChannel(String status) {
        sync.upsertChannel(new SyncChannelCheckpoint(null, originalTask.id(), CHANNEL,
                sourceProfile.seedEndpoints().get(0), null, replicationId, receivedOffset.get(),
                appliedOffset.get(), status, Instant.now(), Instant.now()));
    }

    private void saveMetricThrottled() {
        long now = System.nanoTime();
        if (now - lastMetricNanos < metricIntervalNanos)
            return;
        lastMetricNanos = now;
        saveMetric(false);
    }

    private void pruneSpoolThrottled() throws IOException {
        long now = System.nanoTime();
        if (now - lastPruneNanos < TimeUnit.SECONDS.toNanos(1))
            return;
        lastPruneNanos = now;
        spool.pruneCommandsThrough(appliedOffset.get());
    }

    private void saveMetric(boolean forcedCaughtUp) {
        Instant now = Instant.now();
        long elapsedMillis = Math.max(1, Duration.between(lastMetricAt, now).toMillis());
        long currentReceived = receivedOffset.get();
        long currentApplied = appliedOffset.get();
        long sourceBytesPerSecond = Math.max(0, currentReceived - lastMetricReceived) * 1000 / elapsedMillis;
        long targetBytesPerSecond = Math.max(0, currentApplied - lastMetricApplied) * 1000 / elapsedMillis;
        long gap = Math.max(0, receivedOffset.get() - appliedOffset.get());
        Long timestampLag = lastAppliedHeartbeatMillis <= 0
                ? null
                : (now.toEpochMilli() - lastAppliedHeartbeatMillis) / 1000;
        Long reportedTimestampLag = timestampLag;
        if (forcedCaughtUp && reportedTimestampLag == null)
            reportedTimestampLag = 0L;
        Long estimatedLag = targetBytesPerSecond > 0 ? gap / targetBytesPerSecond : null;
        Long eta = calculateCatchUpEta(gap, sourceBytesPerSecond, targetBytesPerSecond);
        sync.saveMetric(new SyncMetricSnapshot(null, originalTask.id(), CHANNEL,
                reportedTimestampLag, estimatedLag, gap, 0,
                sourceBytesPerSecond, targetBytesPerSecond, eta,
                "OFFSET_THROUGHPUT", gap == 0 ? "HIGH" : "MEDIUM", now));
        lastMetricReceived = currentReceived;
        lastMetricApplied = currentApplied;
        lastMetricAt = now;
    }

    static Long calculateCatchUpEta(long gap, long sourceBytesPerSecond, long targetBytesPerSecond) {
        if (gap == 0)
            return 0L;
        if (targetBytesPerSecond <= sourceBytesPerSecond)
            return null;
        return gap / Math.max(1, targetBytesPerSecond - sourceBytesPerSecond);
    }

    static boolean shouldLeaveCaughtUp(boolean caughtUp, long appliedOffset, long receivedOffset,
            boolean applyQueueEmpty) {
        return caughtUp && (appliedOffset < receivedOffset || !applyQueueEmpty);
    }

    private long heartbeatTimestamp(ReplicationCommand command) {
        List<byte[]> arguments = command.arguments();
        if (!"SET".equals(command.name()) || arguments.size() < 3
                || !java.util.Arrays.equals(arguments.get(1), heartbeatKey))
            return 0;
        try {
            return Long.parseLong(
                    new String(arguments.get(2), java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NumberFormatException ignored) {
            throw new SyncBlockedException("BLOCKED_INVALID_HEARTBEAT", "task heartbeat value is invalid");
        }
    }

    private int selectedDatabase(ReplicationCommand command) {
        if (command.arguments().size() != 2)
            throw new SyncBlockedException("BLOCKED_INVALID_SELECT", "SELECT has an invalid argument count");
        try {
            int database = Integer.parseInt(new String(
                    command.arguments().get(1), java.nio.charset.StandardCharsets.US_ASCII));
            if (database < 0)
                throw new NumberFormatException();
            return database;
        } catch (NumberFormatException error) {
            throw new SyncBlockedException("BLOCKED_INVALID_SELECT", "SELECT database is invalid");
        }
    }

    private int taskFullApplyConcurrency() {
        int configured = limits.fullApplyConcurrency();
        return configured > 0 ? configured : fullApplyConcurrency;
    }

    private int taskFullApplyPipelineSize() {
        int configured = limits.fullApplyPipelineSize();
        return configured > 0 ? configured : fullApplyPipelineSize;
    }

    private void maybeWriteHeartbeat() throws IOException {
        long now = System.nanoTime();
        if (now - lastHeartbeatWriteNanos < TimeUnit.SECONDS.toNanos(1))
            return;
        leaseGuard.assertValid();
        heartbeatSource.writeHeartbeat(System.currentTimeMillis());
        lastHeartbeatWriteNanos = now;
    }

    private List<String> patterns(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid sync key filter", error);
        }
    }

    private void requirePrepared() {
        if (spool == null || target == null || generation < 1)
            throw new IllegalStateException("sync runner is not prepared and leased");
    }

    private void waitForApplyToStop() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (activeApplies.get() > 0) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("timed out waiting for target checkpoint");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private void waitForApplyPermission() {
        while (!cancelled.get() && !stoppedByFailure.get()) {
            leaseGuard.assertValid();
            synchronized (applyGate) {
                if (!paused.get()) {
                    activeApplies.incrementAndGet();
                    return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new CancellationException("sync runner stopped before target apply");
    }

    private void targetApplyFinished() {
        activeApplies.decrementAndGet();
    }

    private void waitForPipeline(Duration timeout) {
        Future<?> future = pipeline;
        if (future == null)
            return;
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining sync task", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("sync pipeline failed", error.getCause());
        } catch (TimeoutException error) {
            throw new IllegalStateException("timed out draining sync task", error);
        }
    }

    private void fail(Throwable error) {
        if (cancelled.get())
            return;
        stoppedByFailure.set(true);
        lastFailure = safe(error);
        if (error instanceof TargetCommandSession.FencingException
                || error instanceof LeaseGuard.LeaseLostException) {
            phase = "LEASE_LOST";
            leaseGuard.revoke();
            sync.updateRuntimeObservation(originalTask.id(),
                    targetFence == null ? "" : targetFence.workerId(), "LEASE_LOST",
                    targetFence == null ? null : targetFence.generation(),
                    targetFence == null ? null : targetFence.publishedAt(), null, safe(error));
            sync.appendTaskEvent(originalTask.id(), "sync:runner",
                    "OLD_WORKER_REJECTED generation=" + generation + " reason=" + safe(error));
            closeSource();
            return;
        }
        if (error instanceof SyncBlockedException blocked) {
            phase = "BLOCKED";
            sync.updateRuntimeObservation(originalTask.id(), targetFence.workerId(), "BLOCKED",
                    targetFence.generation(), targetFence.publishedAt(), blocked.reason(), safe(error));
            sync.appendTaskEvent(originalTask.id(), "sync:" + targetFence.workerId(),
                    ("BLOCKED_REQUIRES_FULL_RESYNC".equals(blocked.reason()) ? "FULL_RESYNC_REQUIRED" : "BLOCKED")
                            + " generation=" + generation + " reason=" + blocked.reason());
            reporter.transition(originalTask.id(), SyncTaskStatus.BLOCKED, null, blocked.reason(),
                    safe(error), "sync runner blocked");
        } else {
            phase = "FAILED";
            reporter.transition(originalTask.id(), SyncTaskStatus.FAILED, null, null, safe(error),
                    "sync runner failed");
        }
        closeSource();
    }

    String lastFailure() {
        return lastFailure;
    }

    String diagnostics() {
        return "received=" + receivedOffset.get() + ", applied=" + appliedOffset.get() + ", readerStarted="
                + readerStarted + ", readerDone=" + (reader != null && reader.isDone());
    }

    private void closeSource() {
        SourceReplicationSession current = source;
        source = null;
        closeQuietly(current);
    }

    private void publishTargetFence(String recoveryAction) {
        try {
            leaseGuard.assertValid();
            phase = "FENCE_PUBLISHING";
            TargetCommandSession.FencePublication publication = target.publishFence(targetFence, leaseGuard);
            checkpointAtFence = publication.checkpoint();
            String action = spoolFallback ? "RECOVERING_PSYNC" : recoveryAction;
            sync.updateRuntimeObservation(originalTask.id(), targetFence.workerId(), "FENCE_PUBLISHED",
                    targetFence.generation(), targetFence.publishedAt(), action, null);
            sync.appendTaskEvent(originalTask.id(), "sync:" + targetFence.workerId(),
                    "FENCE_PUBLISHED generation=" + targetFence.generation()
                            + " runtime=" + targetFence.runtimeId() + " recovery=" + action);
            phase = recovery ? "TAKEOVER_CLAIMED" : "PREPARED";
        } catch (IOException error) {
            throw new UncheckedIOException("cannot publish target Redis fence", error);
        }
    }

    private void prepareSpool() throws IOException {
        EncryptedSpool primary = new EncryptedSpool(dataDirectory, originalTask.id(),
                spoolKeys.taskKey(originalTask.id()), segmentBytes, originalTask.spoolLimitBytes());
        try {
            primary.prepare();
            spool = primary;
        } catch (EncryptedSpool.SpoolLockedException error) {
            primary.close();
            if (!recovery)
                throw error;
            spoolFallback = true;
            Path takeoverDirectory = dataDirectory.resolve("takeover-" + java.util.UUID.randomUUID());
            spool = new EncryptedSpool(takeoverDirectory, originalTask.id(),
                    spoolKeys.taskKey(originalTask.id()), segmentBytes, originalTask.spoolLimitBytes());
            spool.prepare();
        }
    }

    private void reportRecovery(String runtimePhase, String event) {
        phase = runtimePhase;
        sync.updateRuntimeObservation(originalTask.id(), targetFence.workerId(), runtimePhase,
                targetFence.generation(), targetFence.publishedAt(), runtimePhase, null);
        sync.appendTaskEvent(originalTask.id(), "sync:" + targetFence.workerId(),
                event + " generation=" + targetFence.generation() + " runtime=" + targetFence.runtimeId());
    }

    private static String safe(Throwable error) {
        String value = error.getMessage();
        if (value == null)
            value = error.getClass().getSimpleName();
        return value.substring(0, Math.min(1000, value.length()));
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null)
            return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Best effort during lifecycle cleanup.
        }
    }
}
