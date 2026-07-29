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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Multi-channel runner used whenever either side is Redis Cluster. Every source master owns an independent PSYNC
 * channel and spool. Cluster targets commit business commands with a checkpoint and fence key in the same Redis slot.
 */
final class ClusterSyncTaskRunner implements SyncTaskRunner {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private final SyncTask task;
    private final boolean recovery;
    private final RedisConnectionProfileProvider profiles;
    private final SyncRepository sync;
    private final SyncRunnerStateReporter reporter;
    private final SpoolKeyProvider spoolKeys;
    private final RedisDataEndpointResolver endpoints;
    private final ObjectMapper json;
    private final Path dataDirectory;
    private final long segmentBytes;
    private final Duration connectTimeout;
    private final int fullConcurrency;
    private final int fullQueueCapacity;
    private final int fullPipelineSize;
    private final long fullTransactionBytes;
    private final Duration metricInterval;
    private final LeaseGuard leaseGuard;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger activeApplies = new AtomicInteger();
    private final Object applyGate = new Object();
    private final Object throttleGate = new Object();
    private final Semaphore targetApplyPermits;
    private final List<Channel> channels = new CopyOnWriteArrayList<>();
    private volatile SyncTask limits;
    private volatile String phase = "CREATED";
    private volatile TargetFence fence;
    private volatile long generation;
    private volatile boolean finishing;
    private volatile String lastFailure;
    private long lastApplyNanos;
    private RedisConnectionProfile sourceProfile;
    private RedisConnectionProfile targetProfile;

    ClusterSyncTaskRunner(SyncTask task, boolean recovery, RedisConnectionProfileProvider profiles,
            SyncRepository sync, SyncRunnerStateReporter reporter, SpoolKeyProvider spoolKeys,
            RedisDataEndpointResolver endpoints, ObjectMapper json, Path dataDirectory, long segmentBytes,
            Duration connectTimeout, int fullConcurrency, int fullQueueCapacity, int fullPipelineSize,
            long fullTransactionBytes, Duration leaseSafetyMargin, Duration metricInterval) {
        this.task = task;
        this.recovery = recovery;
        this.profiles = profiles;
        this.sync = sync;
        this.reporter = reporter;
        this.spoolKeys = spoolKeys;
        this.endpoints = endpoints;
        this.json = json;
        this.dataDirectory = dataDirectory;
        this.segmentBytes = segmentBytes;
        this.connectTimeout = connectTimeout;
        this.fullConcurrency = fullConcurrency;
        this.fullQueueCapacity = fullQueueCapacity;
        this.fullPipelineSize = fullPipelineSize;
        this.fullTransactionBytes = fullTransactionBytes;
        this.metricInterval = metricInterval;
        this.leaseGuard = new LeaseGuard(leaseSafetyMargin);
        this.targetApplyPermits = new Semaphore(task.fullApplyConcurrency() > 0
                ? task.fullApplyConcurrency()
                : fullConcurrency);
        this.limits = task;
    }

    @Override
    public void prepare() {
        if (task.id() == null || task.fullSyncEpoch() == null || task.fullSyncEpoch().isBlank())
            throw new IllegalStateException("persisted task and full sync epoch are required");
        try {
            sourceProfile = profiles.get(task.sourceClusterId());
            targetProfile = profiles.get(task.targetClusterId());
            if (sourceProfile.mode() != ClusterMode.CLUSTER && targetProfile.mode() != ClusterMode.CLUSTER)
                throw new IllegalStateException("Cluster runner requires a Cluster source or target");
            if (sourceProfile.mode() == ClusterMode.CLUSTER && task.sourceDb() != 0
                    || targetProfile.mode() == ClusterMode.CLUSTER && task.targetDb() != 0)
                throw new SyncBlockedException("BLOCKED_INVALID_DATABASE", "Redis Cluster supports DB 0 only");
            KeyFilter filter = new KeyFilter(patterns(task.includePatternsJson()),
                    patterns(task.excludePatternsJson()));
            List<SourceSpec> sources = sourceSpecs();
            long perChannelLimit = Math.max(segmentBytes, task.spoolLimitBytes() / Math.max(1, sources.size()));
            int taskConcurrency = task.fullApplyConcurrency() > 0 ? task.fullApplyConcurrency() : fullConcurrency;
            int baseConcurrency = Math.max(1, taskConcurrency / sources.size());
            int extraConcurrency = Math.max(0, taskConcurrency - baseConcurrency * sources.size());
            int perChannelQueue = Math.max(1,
                    (int) Math.ceil((double) fullQueueCapacity / sources.size()));
            for (int index = 0; index < sources.size(); index++) {
                int channelConcurrency = baseConcurrency + (index < extraConcurrency ? 1 : 0);
                Channel channel = new Channel(sources.get(index), filter, perChannelLimit, channelConcurrency,
                        Math.max(perChannelQueue, channelConcurrency));
                channels.add(channel);
                channel.initialize();
            }
            phase = "PREPARED";
        } catch (IOException error) {
            close();
            throw new UncheckedIOException("cannot prepare Cluster sync runner", error);
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
        fence = new TargetFence(task.fullSyncEpoch(), generation, runtime.runtimeId(), runtime.leaseOwner(),
                Instant.now());
        for (Channel channel : channels)
            channel.setFence(fence);
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
        phase = "FENCE_PUBLISHING";
        for (Channel channel : channels)
            channel.start(false);
        phase = "FULL_SYNCING";
        reporter.transition(task.id(), SyncTaskStatus.FULL_SYNCING, null, null, null,
                "Cluster source channels started");
    }

    @Override
    public void pause() {
        synchronized (applyGate) {
            paused.set(true);
        }
        phase = "PAUSING";
        waitForApplies();
        phase = "PAUSED";
    }

    @Override
    public void resume() {
        requirePrepared();
        synchronized (applyGate) {
            paused.set(false);
        }
        phase = "RESUMING";
        for (Channel channel : channels)
            channel.start(true);
    }

    @Override
    public void finish() {
        finishing = true;
        for (Channel channel : channels)
            channel.finish();
        phase = "FINISHED";
    }

    @Override
    public void cancel() {
        leaseGuard.revoke();
        cancelled.set(true);
        synchronized (applyGate) {
            paused.set(false);
        }
        for (Channel channel : channels)
            channel.close();
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
        return channels.stream().mapToLong(Channel::spoolBytes).sum();
    }

    String lastFailure() {
        return lastFailure;
    }

    @Override
    public void close() {
        leaseGuard.revoke();
        cancelled.set(true);
        for (Channel channel : channels)
            channel.close();
        closeQuietly(sourceProfile);
        closeQuietly(targetProfile);
    }

    private List<SourceSpec> sourceSpecs() throws IOException {
        if (sourceProfile.mode() != ClusterMode.CLUSTER) {
            RedisEndpoint endpoint = endpoints.resolvePrimary(sourceProfile);
            return List.of(new SourceSpec("source", endpoint, ClusterTargetRouter.allSlots()));
        }
        List<RedisDataEndpointResolver.ClusterMaster> ranges = endpoints.resolveClusterMasters(sourceProfile);
        Map<String, SourceSpecBuilder> grouped = new LinkedHashMap<>();
        for (RedisDataEndpointResolver.ClusterMaster range : ranges) {
            String identity = range.nodeId() == null || range.nodeId().isBlank()
                    ? range.endpoint().host() + "-" + range.endpoint().port()
                    : range.nodeId();
            String channel = "master-" + identity.replaceAll("[^A-Za-z0-9._-]", "_");
            SourceSpecBuilder builder = grouped.computeIfAbsent(identity,
                    ignored -> new SourceSpecBuilder(channel, range.endpoint()));
            if (!builder.endpoint.equals(range.endpoint()))
                throw new SyncBlockedException("BLOCKED_CLUSTER_TOPOLOGY",
                        "one Cluster master identity resolved to multiple endpoints");
            builder.slots.set(range.slotStart(), range.slotEnd() + 1);
        }
        return grouped.values().stream().map(SourceSpecBuilder::build).toList();
    }

    private List<String> patterns(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid sync key filter", error);
        }
    }

    private SyncCommandPolicy commandPolicy() {
        try {
            return json.readValue(task.commandPolicyJson(), SyncCommandPolicy.class);
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid sync command policy", error);
        }
    }

    private void requirePrepared() {
        if (channels.isEmpty() || generation < 1)
            throw new IllegalStateException("Cluster runner is not prepared and leased");
    }

    private void waitForApplies() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (activeApplies.get() > 0) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("timed out waiting for Cluster target applies");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private void beforeApply() {
        while (!cancelled.get()) {
            leaseGuard.assertValid();
            synchronized (applyGate) {
                if (!paused.get() && targetApplyPermits.tryAcquire()) {
                    activeApplies.incrementAndGet();
                    return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new CancellationException("Cluster runner stopped");
    }

    private void afterApply() {
        activeApplies.decrementAndGet();
        targetApplyPermits.release();
    }

    private void throttle(ReplicationCommand command, int operations) {
        if (operations <= 0)
            return;
        long bytes = Math.max(1, command.endOffset() - command.startOffset() + 1);
        long ops = limits.rateLimitOps();
        long byOps = ops <= 0 ? 0 : (long) Math.ceil(1_000_000_000D * operations / ops);
        long bandwidth = limits.bandwidthLimitBytesPerSecond();
        long byBytes = bandwidth <= 0 ? 0 : (long) Math.ceil(1_000_000_000D * bytes / bandwidth);
        long interval = Math.max(byOps, byBytes);
        synchronized (throttleGate) {
            long now = System.nanoTime();
            long wait = lastApplyNanos + interval - now;
            if (wait > 0)
                LockSupport.parkNanos(wait);
            lastApplyNanos = System.nanoTime();
        }
    }

    private synchronized void channelIncremental() {
        if (channels.stream().allMatch(Channel::incremental)) {
            phase = "INCR_SYNCING";
            reporter.transition(task.id(), SyncTaskStatus.INCR_SYNCING, null, null, null,
                    "all Cluster source channels entered incremental replication");
        }
    }

    private synchronized void channelCaughtUp() {
        if (channels.stream().allMatch(Channel::caughtUp)) {
            phase = "CAUGHT_UP";
            reporter.transition(task.id(), SyncTaskStatus.CAUGHT_UP, 0L, null, null,
                    "all Cluster source channels caught up");
        }
    }

    private synchronized void channelFailed(Channel channel, Throwable error) {
        if (cancelled.get())
            return;
        channel.fullProgress.failed();
        String safe = safe(error);
        lastFailure = channel.spec.channel() + " (" + channel.sourceEndpoint + ", RDB "
                + channel.fullRdbBytes + " bytes): " + safe;
        if (error instanceof SyncBlockedException blocked) {
            phase = "BLOCKED";
            reporter.transition(task.id(), SyncTaskStatus.BLOCKED, null, blocked.reason(), safe,
                    "Cluster channel blocked: " + channel.spec.channel());
        } else {
            phase = "FAILED";
            reporter.transition(task.id(), SyncTaskStatus.FAILED, null, null, safe,
                    "Cluster channel failed: " + channel.spec.channel());
        }
        cancelled.set(true);
        for (Channel value : channels)
            if (value != channel)
                value.close();
    }

    private static String safe(Throwable error) {
        String value = error.getMessage();
        if (value == null)
            value = error.getClass().getSimpleName();
        return value.substring(0, Math.min(1000, value.length()));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null)
            return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during lifecycle cleanup.
        }
    }

    private final class Channel implements AutoCloseable {
        private final SourceSpec spec;
        private final KeyFilter filter;
        private final CommandPlanner planner;
        private final int restoreConcurrency;
        private final int restoreQueueCapacity;
        private final byte[] heartbeatKey;
        private final EncryptedSpool spool;
        private final FullSyncProgressTracker fullProgress;
        private final BlockingQueue<ReplicationCommand> queue = new LinkedBlockingQueue<>(10_000);
        private final ExecutorService executor;
        private final AtomicLong received = new AtomicLong(-1);
        private final AtomicLong applied = new AtomicLong(-1);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile boolean incremental;
        private volatile boolean caughtUp;
        private volatile int sourceDatabase;
        private volatile String replicationId;
        private volatile long fullRdbBytes;
        private volatile RedisEndpoint sourceEndpoint;
        private volatile SourceReplicationSession source;
        private volatile TargetCommandSession singleTarget;
        private volatile TargetCommandSession heartbeatSource;
        private volatile ClusterTargetRouter routedTarget;
        private volatile Future<?> pipeline;
        private volatile Future<?> reader;
        private volatile TargetFence channelFence;
        private long lastMetricNanos;
        private long lastSummaryNanos;
        private long lastHeartbeatNanos;
        private long lastHeartbeatMillis;
        private long lastReceivedMetric;
        private long lastAppliedMetric;
        private Instant lastMetricAt = Instant.now();

        private Channel(SourceSpec spec, KeyFilter filter, long spoolLimit, int restoreConcurrency,
                int restoreQueueCapacity) {
            this.spec = spec;
            this.filter = filter;
            this.restoreConcurrency = restoreConcurrency;
            this.restoreQueueCapacity = restoreQueueCapacity;
            this.sourceEndpoint = spec.endpoint();
            int heartbeatSlot = spec.slots().nextSetBit(0);
            this.heartbeatKey = sourceProfile.mode() == ClusterMode.CLUSTER
                    ? ClusterSlotKeyspace.heartbeat(task.id(), spec.channel(), heartbeatSlot)
                    : ("__redis_ops_sync_hb__:{" + task.id() + "}:" + spec.channel())
                            .getBytes(StandardCharsets.US_ASCII);
            this.planner = new CommandPlanner(filter, targetProfile.mode() == ClusterMode.CLUSTER, heartbeatKey,
                    commandPolicy());
            this.spool = new EncryptedSpool(dataDirectory, task.id(), spec.channel(),
                    spoolKeys.taskKey(task.id()), segmentBytes, spoolLimit);
            this.fullProgress = new FullSyncProgressTracker(task.id(), task.fullSyncEpoch(), spec.channel(),
                    restoreConcurrency, sync);
            this.executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "redis-cluster-sync-" + task.id() + "-" + spec.channel());
                thread.setDaemon(true);
                return thread;
            });
        }

        private void initialize() throws IOException {
            spool.prepare();
            if (targetProfile.mode() == ClusterMode.CLUSTER)
                routedTarget = new ClusterTargetRouter(targetProfile, endpoints, task.id(), spec.channel(),
                        connectTimeout);
            else
                singleTarget = new TargetCommandSession(targetProfile, endpoints.resolvePrimary(targetProfile),
                        task.targetDb(), task.id(), connectTimeout, spec.channel());
            heartbeatSource = heartbeatSession();
        }

        private void setFence(TargetFence fence) {
            this.channelFence = fence;
        }

        private void start(boolean resume) {
            if (pipeline != null && !pipeline.isDone())
                return;
            pipeline = executor.submit(() -> run(resume || recovery));
        }

        private void run(boolean recover) {
            try {
                OptionalLong checkpoint = publishFence();
                if (recover) {
                    if (checkpoint.isPresent()) {
                        TargetCheckpoint targetCheckpoint = targetCheckpoint();
                        replicationId = targetCheckpoint.replicationId();
                        sourceDatabase = targetCheckpoint.sourceDatabase();
                        applied.set(targetCheckpoint.appliedOffset());
                        received.set(targetCheckpoint.appliedOffset());
                        replaySpool();
                        connectPartial(applied.get(), true);
                        applyLive();
                        return;
                    }
                    EncryptedSpool.FullMetadata metadata = spool.fullMetadata()
                            .orElseThrow(() -> new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                                    "channel has no target checkpoint or local full RDB spool"));
                    replicationId = metadata.replicationId();
                    applyRdb(metadata.baseOffset());
                    replaySpool();
                    connectPartial(applied.get(), true);
                    applyLive();
                    return;
                }
                source = new SourceReplicationSession(sourceProfile, sourceEndpoint, connectTimeout);
                ReplicationReply reply = source.start(sourceProfile, null, -1);
                if (!(reply instanceof ReplicationReply.FullResync full))
                    throw new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                            "new Cluster channel did not receive FULLRESYNC");
                replicationId = full.replicationId();
                received.set(full.offset());
                applied.set(full.offset());
                fullProgress.startReceiving(full.transfer().length());
                fullRdbBytes = source.spoolRdb(full, spool, fullProgress::received);
                fullProgress.rdbReceived(fullRdbBytes);
                spool.saveFullMetadata(full.replicationId(), full.offset());
                source.acknowledge(full.offset());
                source.readTimeout(Duration.ofSeconds(1));
                reader = executor.submit(this::readLoop);
                applyRdb(full.offset());
                replaySpool();
                applyLive();
            } catch (Throwable error) {
                stopped.set(true);
                channelFailed(this, error);
            }
        }

        private OptionalLong publishFence() throws IOException {
            leaseGuard.assertValid();
            if (routedTarget != null)
                return routedTarget.publishFences(spec.slots(), channelFence, leaseGuard);
            TargetCommandSession.FencePublication publication = singleTarget.publishFence(channelFence, leaseGuard);
            return publication.checkpoint().isPresent()
                    ? OptionalLong.of(publication.checkpoint().get().appliedOffset())
                    : OptionalLong.empty();
        }

        private TargetCheckpoint targetCheckpoint() throws IOException {
            if (routedTarget != null)
                return routedTarget.checkpoint()
                        .orElseThrow(() -> new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                                "target Cluster channel checkpoint disappeared after fence publication"));
            return singleTarget.checkpoint()
                    .orElseThrow(() -> new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                            "target channel checkpoint disappeared after fence publication"));
        }

        private void applyRdb(long baseOffset) throws IOException {
            if (routedTarget != null)
                applyClusterRdb();
            else
                applySingleRdb();
            TargetCheckpoint checkpoint = checkpoint(baseOffset);
            if (routedTarget != null)
                routedTarget.initializeFullCheckpoint(spec.slots(), checkpoint, channelFence, leaseGuard);
            else
                singleTarget.apply(List.of(), checkpoint, channelFence, leaseGuard);
            applied.set(baseOffset);
            fullProgress.completed();
            spool.discardFullRdb();
            incremental = true;
            updateChannel("INCR_SYNCING");
            channelIncremental();
        }

        private void applyClusterRdb() throws IOException {
            try (var restores = new ClusterFullRestorePool(routedTarget, channelFence, leaseGuard,
                    restoreConcurrency,
                    restoreQueueCapacity, ClusterSyncTaskRunner.this::beforeApply,
                    ClusterSyncTaskRunner.this::afterApply, fullProgress::applied);
                    var input = spool.openRdb()) {
                parseRdb(input, restores::submit, payload -> routedTarget.loadFunction(payload, channelFence,
                        leaseGuard));
                restores.awaitCompletion();
            }
        }

        private void applySingleRdb() throws IOException {
            try (var restores = new FullRestorePool(targetProfile, endpoints, task.targetDb(), task.id(),
                    spec.channel(), connectTimeout, restoreConcurrency, restoreQueueCapacity,
                    limits.fullApplyPipelineSize() > 0 ? limits.fullApplyPipelineSize() : fullPipelineSize,
                    fullTransactionBytes, channelFence, leaseGuard,
                    ClusterSyncTaskRunner.this::beforeApply, ClusterSyncTaskRunner.this::afterApply,
                    fullProgress::applied);
                    var input = spool.openRdb()) {
                parseRdb(input, restores::submit,
                        payload -> singleTarget.loadFunction(payload, channelFence, leaseGuard));
                restores.awaitCompletion();
            }
        }

        private void parseRdb(java.io.InputStream input, RestoreConsumer restores, FunctionConsumer functions)
                throws IOException {
            try {
                RdbStreamParser parser = new RdbStreamParser(input);
                parser.parse(event -> {
                    try {
                        boolean accepted = false;
                        if (event instanceof RdbEvent.KeyValue keyValue
                                && keyValue.database() == task.sourceDb()
                                && filter.accepts(keyValue.key())
                                && sourceOwns(keyValue.key())) {
                            restores.accept(new TargetCommandSession.RestoreRequest(keyValue.key(),
                                    keyValue.absoluteExpireMillis(), keyValue.dumpPayload()));
                            accepted = true;
                        } else if (event instanceof RdbEvent.FunctionLibrary function) {
                            functions.accept(function.payload());
                        }
                        fullProgress.parsed(parser.position(), accepted);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
                fullProgress.parsingComplete(parser.position());
            } catch (UncheckedIOException error) {
                throw error.getCause();
            }
        }

        private boolean sourceOwns(byte[] key) {
            return sourceProfile.mode() != ClusterMode.CLUSTER || spec.slots().get(RedisSlot.of(key));
        }

        private void readLoop() {
            while (!cancelled.get() && !stopped.get()) {
                try {
                    leaseGuard.assertValid();
                    ReplicationCommand command = source.readCommand();
                    spool.append(command);
                    received.set(command.endOffset());
                    source.acknowledge(command.endOffset());
                    queue.put(command);
                    writeHeartbeat();
                    updateChannelThrottled();
                    while (!cancelled.get() && spool.bytes() >= limits.spoolLimitBytes() * 9 / 10)
                        Thread.sleep(100);
                } catch (SocketTimeoutException timeout) {
                    if (finishing)
                        return;
                    try {
                        writeHeartbeat();
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable error) {
                    if (cancelled.get() || stopped.get() || finishing)
                        return;
                    try {
                        reconnect(received.get());
                    } catch (IOException reconnectFailure) {
                        stopped.set(true);
                        channelFailed(this, reconnectFailure);
                        return;
                    }
                }
            }
        }

        private void applyLive() throws Exception {
            while (!cancelled.get() && !stopped.get()) {
                if (paused.get()) {
                    Thread.sleep(50);
                    continue;
                }
                ReplicationCommand first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first != null) {
                    List<ReplicationCommand> batch = new ArrayList<>(100);
                    batch.add(first);
                    queue.drainTo(batch, 99);
                    applyBatch(batch);
                }
                if (finishing && (reader == null || reader.isDone()) && queue.isEmpty()
                        && applied.get() >= received.get())
                    return;
                if (!caughtUp && applied.get() == received.get() && queue.isEmpty()) {
                    caughtUp = true;
                    channelCaughtUp();
                }
                saveMetric();
            }
        }

        private void replaySpool() throws IOException {
            List<ReplicationCommand> commands = spool.commandsAfter(applied.get());
            for (int index = 0; index < commands.size(); index += 100)
                applyBatch(commands.subList(index, Math.min(index + 100, commands.size())));
        }

        private void applyBatch(List<ReplicationCommand> commands) throws IOException {
            beforeApply();
            try {
                List<CommandPlan.PlannedCommand> planned = new ArrayList<>();
                ReplicationCommand last = null;
                long heartbeat = 0;
                for (ReplicationCommand command : commands) {
                    if (command.endOffset() <= applied.get())
                        continue;
                    CommandPlan plan;
                    if ("SELECT".equals(command.name())) {
                        sourceDatabase = selectedDatabase(command);
                        plan = CommandPlan.skip();
                    } else if (sourceDatabase == task.sourceDb()) {
                        plan = planner.plan(command);
                    } else {
                        plan = CommandPlan.skip();
                    }
                    if (plan.disposition() == CommandPlan.Disposition.BLOCK)
                        throw new SyncBlockedException("BLOCKED_UNSUPPORTED_COMMAND",
                                command.name() + " at offset " + command.endOffset() + ": " + plan.reason());
                    throttle(command, plan.commands().size());
                    planned.addAll(plan.commands());
                    heartbeat = Math.max(heartbeat, heartbeatTimestamp(command));
                    last = command;
                }
                if (last == null)
                    return;
                TargetCheckpoint checkpoint = checkpoint(last.endOffset());
                if (routedTarget != null)
                    routedTarget.apply(planned, checkpoint, channelFence, leaseGuard);
                else
                    singleTarget.apply(planned, checkpoint, channelFence, leaseGuard);
                applied.set(last.endOffset());
                lastHeartbeatMillis = Math.max(lastHeartbeatMillis, heartbeat);
                caughtUp = false;
                updateChannelThrottled();
                spool.pruneCommandsThrough(applied.get());
            } finally {
                afterApply();
            }
        }

        private void connectPartial(long offset, boolean startReader) throws IOException {
            reconnectEndpoint();
            source = new SourceReplicationSession(sourceProfile, sourceEndpoint, connectTimeout);
            ReplicationReply reply = source.start(sourceProfile, replicationId, offset);
            if (!(reply instanceof ReplicationReply.Continue continued))
                throw new SyncBlockedException("BLOCKED_REQUIRES_FULL_RESYNC",
                        "source channel backlog cannot continue at offset " + offset);
            if (continued.replicationId() != null)
                replicationId = continued.replicationId();
            source.continueCommands(offset);
            source.readTimeout(Duration.ofSeconds(1));
            if (startReader)
                reader = executor.submit(this::readLoop);
        }

        private void reconnect(long offset) throws IOException {
            closeQuietly(source);
            connectPartial(offset, false);
        }

        private void reconnectEndpoint() throws IOException {
            if (sourceProfile.mode() != ClusterMode.CLUSTER) {
                sourceEndpoint = endpoints.resolvePrimary(sourceProfile);
                return;
            }
            int anchor = spec.slots().nextSetBit(0);
            List<RedisDataEndpointResolver.ClusterMaster> ranges = endpoints.resolveClusterMasters(sourceProfile);
            RedisEndpoint owner = ranges.stream()
                    .filter(range -> range.slotStart() <= anchor && anchor <= range.slotEnd())
                    .map(RedisDataEndpointResolver.ClusterMaster::endpoint).findFirst()
                    .orElseThrow(() -> new SyncBlockedException("BLOCKED_CLUSTER_TOPOLOGY",
                            "source Cluster lost the channel anchor slot"));
            BitSet current = ClusterTargetRouter.slots(ranges, owner);
            if (!current.equals(spec.slots()))
                throw new SyncBlockedException("BLOCKED_CLUSTER_TOPOLOGY_CHANGED",
                        "source Cluster slot ownership changed while the task was running");
            sourceEndpoint = owner;
        }

        private TargetCommandSession heartbeatSession() throws IOException {
            if (sourceProfile.mode() == ClusterMode.CLUSTER)
                return TargetCommandSession.clusterSlot(sourceProfile, sourceEndpoint, task.id(), connectTimeout,
                        spec.channel(), spec.slots().nextSetBit(0));
            return new TargetCommandSession(sourceProfile, sourceEndpoint, task.sourceDb(), task.id(),
                    connectTimeout, spec.channel());
        }

        private void writeHeartbeat() throws IOException {
            long now = System.nanoTime();
            if (now - lastHeartbeatNanos < TimeUnit.SECONDS.toNanos(1))
                return;
            try {
                heartbeatSource.writeHeartbeat(System.currentTimeMillis());
            } catch (IOException error) {
                closeQuietly(heartbeatSource);
                reconnectEndpoint();
                heartbeatSource = heartbeatSession();
                heartbeatSource.writeHeartbeat(System.currentTimeMillis());
            }
            lastHeartbeatNanos = now;
        }

        private int selectedDatabase(ReplicationCommand command) {
            try {
                return Integer.parseInt(new String(command.arguments().get(1), StandardCharsets.US_ASCII));
            } catch (RuntimeException error) {
                throw new SyncBlockedException("BLOCKED_INVALID_SELECT", "invalid SELECT command");
            }
        }

        private long heartbeatTimestamp(ReplicationCommand command) {
            if (!"SET".equals(command.name()) || command.arguments().size() < 3
                    || !Arrays.equals(command.arguments().get(1), heartbeatKey))
                return 0;
            try {
                return Long.parseLong(new String(command.arguments().get(2), StandardCharsets.US_ASCII));
            } catch (NumberFormatException error) {
                throw new SyncBlockedException("BLOCKED_INVALID_HEARTBEAT", "invalid heartbeat value");
            }
        }

        private TargetCheckpoint checkpoint(long offset) {
            return new TargetCheckpoint(task.fullSyncEpoch(), generation, replicationId, offset, sourceDatabase,
                    Instant.now());
        }

        private void updateChannelThrottled() {
            long now = System.nanoTime();
            if (now - lastSummaryNanos < TimeUnit.SECONDS.toNanos(1))
                return;
            lastSummaryNanos = now;
            updateChannel("RUNNING");
        }

        private void updateChannel(String status) {
            sync.upsertChannel(new SyncChannelCheckpoint(null, task.id(), spec.channel(),
                    sourceEndpoint.host() + ":" + sourceEndpoint.port(), slotRangesJson(spec.slots()),
                    replicationId, received.get(), applied.get(), status, Instant.now(), Instant.now()));
        }

        private void saveMetric() {
            long nowNanos = System.nanoTime();
            if (nowNanos - lastMetricNanos < metricInterval.toNanos())
                return;
            lastMetricNanos = nowNanos;
            Instant now = Instant.now();
            long elapsed = Math.max(1, Duration.between(lastMetricAt, now).toMillis());
            long sourceRate = Math.max(0, received.get() - lastReceivedMetric) * 1000 / elapsed;
            long targetRate = Math.max(0, applied.get() - lastAppliedMetric) * 1000 / elapsed;
            long gap = Math.max(0, received.get() - applied.get());
            Long lag = lastHeartbeatMillis == 0 ? null : (now.toEpochMilli() - lastHeartbeatMillis) / 1000;
            Long eta = targetRate > sourceRate ? gap / Math.max(1, targetRate - sourceRate) : null;
            sync.saveMetric(new SyncMetricSnapshot(null, task.id(), spec.channel(), lag,
                    targetRate == 0 ? null : gap / targetRate, gap, 0, sourceRate, targetRate, eta,
                    lag == null ? "OFFSET_THROUGHPUT" : "TIMESTAMP_WATERMARK",
                    lag == null ? "MEDIUM" : "HIGH", now));
            lastMetricAt = now;
            lastReceivedMetric = received.get();
            lastAppliedMetric = applied.get();
        }

        private void finish() {
            try {
                if (source != null)
                    source.readTimeout(Duration.ofSeconds(1));
                if (pipeline != null)
                    pipeline.get(60, TimeUnit.SECONDS);
                if (applied.get() < received.get())
                    throw new IllegalStateException("Cluster channel did not drain");
            } catch (Exception error) {
                throw new IllegalStateException("cannot finish Cluster channel " + spec.channel(), error);
            }
        }

        private boolean incremental() {
            return incremental;
        }

        private boolean caughtUp() {
            return caughtUp;
        }

        private long spoolBytes() {
            return spool.bytes();
        }

        @Override
        public void close() {
            stopped.set(true);
            closeQuietly(source);
            closeQuietly(singleTarget);
            closeQuietly(heartbeatSource);
            closeQuietly(routedTarget);
            closeQuietly(spool);
            executor.shutdownNow();
        }
    }

    private static String slotRangesJson(BitSet slots) {
        StringJoiner ranges = new StringJoiner(",", "[", "]");
        for (int start = slots.nextSetBit(0); start >= 0;) {
            int end = slots.nextClearBit(start) - 1;
            ranges.add("\"" + (start == end ? Integer.toString(start) : start + "-" + end) + "\"");
            start = slots.nextSetBit(end + 1);
        }
        return ranges.toString();
    }

    @FunctionalInterface
    private interface RestoreConsumer {
        void accept(TargetCommandSession.RestoreRequest request) throws IOException;
    }

    @FunctionalInterface
    private interface FunctionConsumer {
        void accept(byte[] payload) throws IOException;
    }

    private record SourceSpec(String channel, RedisEndpoint endpoint, BitSet slots) {
        private SourceSpec {
            slots = (BitSet) slots.clone();
        }
        @Override
        public BitSet slots() {
            return (BitSet) slots.clone();
        }
    }
    private static final class SourceSpecBuilder {
        private final String channel;
        private final RedisEndpoint endpoint;
        private final BitSet slots = new BitSet(16384);
        private SourceSpecBuilder(String channel, RedisEndpoint endpoint) {
            this.channel = channel;
            this.endpoint = endpoint;
        }
        private SourceSpec build() {
            return new SourceSpec(channel, endpoint, slots);
        }
    }
}
