package io.github.susongyan.redisops.platform.application.distribution;

import io.github.susongyan.redisops.platform.common.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.distribution.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.*;
import java.nio.charset.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DistributionService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DistributionService.class);
    private final DistributionRepository repository;
    private final DistributionScanPort redis;
    private final RedisConnectionProfileProvider profiles;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1,
            r -> daemon(r, "distribution-heartbeat"));
    // checkpoint persistence lives off the scan thread: a slow DB link must not stall scanning
    private final ExecutorService checkpoints = Executors.newSingleThreadExecutor(
            r -> daemon(r, "distribution-checkpoint"));
    private final int maxDuration, maxScanCount, maxGroups, shardParallelism;
    private int minScanIntervalMillis = 10;
    @Value("${distribution.min-scan-interval-ms:10}")
    public void setMinScanIntervalMillis(int value) {
        if (value < 10 || value > 60000)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_SCAN_RATE");
        minScanIntervalMillis = value;
    }
    private volatile boolean closing;
    private final ConcurrentMap<Long, Thread> running = new ConcurrentHashMap<>();
    public DistributionService(DistributionRepository repository, DistributionScanPort redis,
            RedisConnectionProfileProvider profiles,
            @Value("${distribution.concurrent-tasks:1}") int concurrent,
            @Value("${distribution.max-duration-seconds:21600}") int maxDuration,
            @Value("${distribution.max-scan-count:5000}") int maxScanCount,
            @Value("${distribution.max-groups:1000}") int maxGroups,
            @Value("${distribution.max-shard-parallelism:4}") int shardParallelism) {
        if (concurrent < 1 || concurrent > 2 || maxDuration < 1 || maxDuration > 21600 || maxScanCount < 1
                || maxScanCount > 5000
                || maxGroups < 1 || maxGroups > 1000 || shardParallelism < 1 || shardParallelism > 32)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CONFIGURATION");
        this.repository = repository;
        this.redis = redis;
        this.profiles = profiles;
        this.maxDuration = maxDuration;
        this.maxScanCount = maxScanCount;
        this.maxGroups = maxGroups;
        this.shardParallelism = shardParallelism;
        slots = new Semaphore(concurrent);
        executor = Executors.newFixedThreadPool(concurrent, r -> daemon(r, "distribution-scan"));
    }
    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
    public DistributionTask create(DistributionSpec spec) {
        if (spec.legacyRateConfiguration())
            throw new IllegalArgumentException("LEGACY_RATE_CONFIGURATION");
        if (spec.durationSeconds() > maxDuration || spec.scanCount() > maxScanCount || spec.capacity() > maxGroups
                || spec.scanIntervalMillis() < minScanIntervalMillis)
            throw new IllegalArgumentException("DISTRIBUTION_DEPLOYMENT_BUDGET_EXCEEDED");
        validateCluster(spec.clusterId(), spec.database());
        // create-then-execute like every other scan job: the instance that received the request
        // claims the cluster lease in the same transaction as the insert, so a foreign instance's
        // sweep never wins the first claim of a task for a cluster it cannot reach
        if (closing || !slots.tryAcquire())
            throw new BusinessException("DISTRIBUTION_BUSY",
                    "this instance already runs the maximum number of scans");
        DistributionRepository.ClaimedTask created;
        try {
            created = repository.createAndClaim(spec, UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        executor.execute(() -> {
            try {
                execute(created.lease(), created.task());
            } finally {
                slots.release();
            }
        });
        return created.task();
    }
    private void validateCluster(long id, int db) {
        if (id < 1 || db < 0 || db > 15)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_DATABASE");
        try (var profile = profiles.get(id)) {
            if (profile.mode() == ClusterMode.CLUSTER && db != 0)
                throw new IllegalArgumentException("CLUSTER_REQUIRES_DB_ZERO");
        }
    }
    public DistributionTask get(long id) {
        var task = repository.get(id);
        var checkpoint = repository.checkpoint(id);
        return task.withScanNodes(checkpoint.map(c -> c.cursors().stream()
                .map(DistributionCheckpoint.Cursor::shard).toList()).orElse(List.of()))
                .withEstimatedTotal(checkpoint.map(DistributionCheckpoint::estimatedTotal).orElse(0L));
    }
    private void page(int page, int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100)
            throw new IllegalArgumentException("INVALID_PAGE");
    }
    public PageResult<DistributionTask> list(int page, int size) {
        page(page, size);
        return repository.list(page, size);
    }
    public PageResult<DistributionCounter.Entry> groups(long id, int page, int size) {
        page(page, size);
        repository.get(id);
        return repository.groups(id, page, size);
    }
    public DistributionTask control(long id, long version, String action) {
        var result = repository.control(id, version, action);
        if ("resume".equals(action))
            startLocally(id);
        else {
            Thread thread = running.get(id);
            if (thread != null)
                thread.interrupt();
        }
        return result;
    }
    /** Runs a queued task on this instance; the sweep takes over if it cannot start here. */
    private void startLocally(long taskId) {
        if (closing || !slots.tryAcquire())
            return;
        var lease = repository.claimTask(taskId, UUID.randomUUID().toString());
        if (lease.isEmpty()) {
            slots.release();
            return;
        }
        executor.execute(() -> {
            try {
                execute(lease.get(), null);
            } finally {
                slots.release();
            }
        });
    }
    public void cleanup() {
        repository.cleanup(30);
    }
    public record Preview(List<String> samples, long observed, long oversized, long binary, boolean limited,
            int totalShards, int visitedShards, List<DistributionScanPort.Shard> scanNodes) {
        public Preview(List<String> samples, long observed, long oversized, long binary, boolean limited,
                int totalShards, int visitedShards) {
            this(samples, observed, oversized, binary, limited, totalShards, visitedShards, List.of());
        }
    }
    public Preview preview(long cluster, int db) {
        validateCluster(cluster, db);
        if (closing || !slots.tryAcquire())
            throw new BusinessException("REQUEST_IN_PROGRESS", "scan capacity busy");
        DistributionRepository.Lease lease = null;
        try {
            lease = repository.claimPreview(cluster, UUID.randomUUID().toString()).orElseThrow(
                    () -> new BusinessException("REQUEST_IN_PROGRESS", "cluster scan busy or preview rate limited"));
            long until = System.nanoTime() + 15_000_000_000L;
            var topology = redis.topology(cluster, db, until);
            long lastTopology = System.nanoTime();
            List<String> samples = new ArrayList<>();
            long observed = 0, oversized = 0, binary = 0;
            int bytes = 0, visited = 0;
            String[] cursors = new String[topology.shards().size()];
            Arrays.fill(cursors, "0");
            boolean[] done = new boolean[cursors.length];
            boolean[] seen = new boolean[cursors.length];
            int index = 0;
            boolean limit = false;
            while (System.nanoTime() < until && observed < 1000 && samples.size() < 1000 && bytes < 256 * 1024) {
                if (done[index]) {
                    if (all(done))
                        break;
                    index = (index + 1) % done.length;
                    continue;
                }
                if (!repository.renew(lease))
                    throw new IllegalStateException("SCAN_LEASE_LOST");
                long before = System.nanoTime();
                DistributionScanPort.Page batch;
                try {
                    if (System.nanoTime() - lastTopology >= 5_000_000_000L) {
                        if (!topology.fingerprint().equals(redis.topology(cluster, db, until).fingerprint()))
                            throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
                        lastTopology = System.nanoTime();
                    }
                    batch = redis.scan(cluster, db, topology.shards().get(index), cursors[index],
                            Math.min(200, maxScanCount), until);
                } catch (IllegalStateException e) {
                    if (System.nanoTime() >= until) {
                        limit = true;
                        break;
                    }
                    throw e;
                }
                if (!seen[index]) {
                    seen[index] = true;
                    visited++;
                }
                for (byte[] key : batch.keys()) {
                    if (observed >= 1000) {
                        limit = true;
                        break;
                    }
                    observed++;
                    if (key == null) {
                        oversized++;
                        continue;
                    }
                    String text;
                    try {
                        text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(key)).toString();
                    } catch (CharacterCodingException e) {
                        binary++;
                        continue;
                    }
                    if (samples.size() >= 1000 || bytes + key.length > 256 * 1024) {
                        limit = true;
                        break;
                    }
                    samples.add(text);
                    bytes += key.length;
                }
                cursors[index] = batch.cursor();
                done[index] = "0".equals(batch.cursor());
                index = (index + 1) % done.length;
                if (limit)
                    break;
                sleepUntil(Math.min(until,
                        before + Math.max(Math.max(200, minScanIntervalMillis) * 1_000_000L,
                                batch.keys().size() * 1_000_000L)));
            }
            if (System.nanoTime() < until
                    && !topology.fingerprint().equals(redis.topology(cluster, db, until).fingerprint()))
                throw new IllegalStateException("SCAN_TOPOLOGY_UNSTABLE");
            return new Preview(List.copyOf(samples), observed, oversized, binary, limit || !all(done), done.length,
                    visited, topology.shards());
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException("DISTRIBUTION_PREVIEW_FAILED", "preview failed; no samples persisted");
        } finally {
            try {
                if (lease != null)
                    repository.release(lease);
            } finally {
                slots.release();
            }
        }
    }
    private static boolean all(boolean[] values) {
        for (boolean v : values)
            if (!v)
                return false;
        return true;
    }
    public void poll() {
        if (closing || !slots.tryAcquire())
            return;
        try {
            var lease = repository.claim(UUID.randomUUID().toString());
            if (lease.isEmpty()) {
                slots.release();
                return;
            }
            executor.execute(() -> {
                try {
                    execute(lease.get(), null);
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }
    private void execute(DistributionRepository.Lease lease, DistributionTask known) {
        Thread executionThread = Thread.currentThread();
        running.put(lease.taskId(), executionThread);
        AtomicBoolean lost = new AtomicBoolean();
        var heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                if (!repository.renew(lease))
                    lost.set(true);
            } catch (RuntimeException e) {
                lost.set(true);
            }
            if (lost.get())
                executionThread.interrupt();
        }, 5, 5, TimeUnit.SECONDS);
        DistributionCheckpoint committed = null;
        ExecutorService shardWorkers = null;
        boolean persisted = false, requeued = false;
        // halt stops the scan when an async checkpoint reports status drift or a DB outage
        AtomicBoolean halt = new AtomicBoolean(), saveInFlight = new AtomicBoolean();
        long started = System.nanoTime();
        try {
            // the create path hands over the task it just committed: a row born milliseconds ago
            // can still be invisible to another pooled connection on this OB tenant, so re-reading
            // it here races read-your-writes and once failed the task as "not found"
            var task = known != null ? known : repository.get(lease.taskId());
            var spec = task.spec();
            if (spec.legacyRateConfiguration()) {
                repository.save(lease, null, "INCOMPLETE", "LEGACY_RATE_CONFIGURATION");
                return;
            }
            if (spec.scanCount() > maxScanCount || spec.scanIntervalMillis() < minScanIntervalMillis) {
                repository.save(lease, null, "PAUSED", "DEPLOYMENT_BUDGET_CHANGED");
                return;
            }
            committed = known != null ? null : repository.checkpoint(task.id()).orElse(null);
            long deadline = started + Math.max(0, Math.min(spec.durationSeconds(), maxDuration) * 1000L
                    - (committed == null ? 0 : committed.elapsedMillis())) * 1_000_000L;
            if (System.nanoTime() >= deadline) {
                repository.save(lease, committed, "INCOMPLETE", "BUDGET_REACHED");
                return;
            }
            var topology = redis.topology(spec.clusterId(), spec.database(), deadline);
            if (committed != null && !committed.fingerprint().equals(topology.fingerprint())) {
                repository.save(lease, committed, "INCOMPLETE", "TOPOLOGY_CHANGED");
                return;
            }
            // progress denominator: one O(1) DBSIZE per shard, sampled before the workers start.
            // A committed value is reused across pause/resume and instance takeover so the bar
            // stays monotonic; a failed sample degrades to an indeterminate bar, never the scan
            long estimatedTotal = committed == null ? 0 : committed.estimatedTotal();
            if (estimatedTotal == 0)
                try {
                    estimatedTotal = redis.count(spec.clusterId(), spec.database(), topology);
                } catch (RuntimeException e) {
                    log.debug("distribution dbsize sample failed for task {}", lease.taskId());
                }
            var classifier = new DistributionClassifier(spec.rules());
            var counter = new DistributionCounter(spec.mode(), spec.capacity(), spec.rules());
            List<DistributionCheckpoint.Cursor> cursors = new ArrayList<>();
            int interval = spec.scanIntervalMillis();
            long initialElapsed = 0;
            if (committed == null)
                for (var shard : topology.shards())
                    cursors.add(new DistributionCheckpoint.Cursor(shard, "0", false));
            else {
                counter.restore(committed.observed(), committed.entries());
                cursors.addAll(committed.cursors());
                initialElapsed = committed.elapsedMillis();
            }
            long budgetMillis = Math.min(spec.durationSeconds(), maxDuration) * 1000L;
            // cursors and the counter are not thread-safe: every read or write holds this lock,
            // held for well under a millisecond per page so workers never queue on it
            Object lock = new Object();
            Deque<Integer> pending = new ArrayDeque<>();
            for (int i = 0; i < cursors.size(); i++)
                if (!cursors.get(i).completed())
                    pending.add(i);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<String> failure = new AtomicReference<>();
            java.util.function.BooleanSupplier capped = () -> {
                synchronized (lock) {
                    return spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations();
                }
            };
            // shards scan in parallel, each on its own pinned session paced by its own page
            // interval: scan pressure stays bounded per node, and there is no shared pacer
            Runnable worker = () -> {
                ReadOnlyScanPort.Session session = null;
                try {
                    while (!stop.get() && System.nanoTime() < deadline && !capped.getAsBoolean()) {
                        Integer index;
                        synchronized (pending) {
                            index = pending.poll();
                        }
                        if (index == null)
                            return;
                        var cursor = cursors.get(index);
                        // one pinned connection per shard: the per-page form paid a fresh
                        // connect+auth+handshake roundtrip chain on every single page
                        session = redis.open(spec.clusterId(), spec.database(), cursor.shard());
                        int timeouts = 0;
                        while (!stop.get() && System.nanoTime() < deadline && !capped.getAsBoolean()
                                && !cursor.completed()) {
                            long before = System.nanoTime();
                            DistributionScanPort.Page batch;
                            try {
                                batch = session.scan(cursor.cursor(), spec.scanCount());
                                timeouts = 0;
                            } catch (IllegalStateException e) {
                                if ("SCAN_TIMEOUT".equals(e.getMessage()) && ++timeouts < 3)
                                    continue;
                                throw e;
                            }
                            synchronized (lock) {
                                int accepted = 0;
                                for (byte[] key : batch.keys()) {
                                    if (spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations())
                                        break;
                                    counter.accept(classifier.classify(key));
                                    accepted++;
                                }
                                cursors.set(index, new DistributionCheckpoint.Cursor(cursor.shard(), batch.cursor(),
                                        accepted == batch.keys().size() && "0".equals(batch.cursor())));
                                cursor = cursors.get(index);
                            }
                            try {
                                sleepUntil(Math.min(before + interval * 1_000_000L, deadline));
                            } catch (IllegalStateException e) {
                                if (stop.get())
                                    return;
                                throw e;
                            }
                        }
                        session.close();
                        session = null;
                    }
                } catch (RuntimeException e) {
                    // a pacing interrupt is shutdownNow by design: the coordinator owns the
                    // outcome, and when it dies unexpectedly its terminal save already decided it
                    if ("SCAN_INTERRUPTED".equals(e.getMessage()))
                        return;
                    // a failure that only surfaced because shutdownNow interrupted this worker is
                    // not a scan failure: pause/cancel/lease loss already own the outcome
                    if (!stop.get()) {
                        // null messages (e.g. NPE) would leave the coordinator waiting forever
                        failure.compareAndSet(null,
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        log.warn("distribution shard worker failed", e);
                        stop.set(true);
                    }
                } finally {
                    if (session != null)
                        try {
                            session.close();
                        } catch (RuntimeException ignored) {
                            // scan is over; connection teardown errors are irrelevant
                        }
                }
            };
            int workers = Math.min(cursors.size(), shardParallelism);
            shardWorkers = Executors.newFixedThreadPool(workers, r -> daemon(r, "distribution-shard"));
            for (int i = 0; i < workers; i++)
                shardWorkers.execute(worker);
            String drift = null;
            boolean complete = false, budgetOut = false;
            long lastSave = 0, lastTopology = System.nanoTime();
            // the coordinator owns completion/budget detection, topology rechecks and
            // checkpoints; same-instance pause/cancel and lease loss interrupt this thread,
            // which stops the workers quietly without writing a terminal state
            try {
                while (!closing && !lost.get() && !halt.get() && failure.get() == null) {
                    Thread.sleep(150);
                    // a worker may have failed during the sleep: never persist a first
                    // checkpoint for an execution that already died, it must stay requeueable
                    if (failure.get() != null)
                        break;
                    long elapsed = initialElapsed + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    synchronized (lock) {
                        complete = cursors.stream().allMatch(DistributionCheckpoint.Cursor::completed);
                        budgetOut = elapsed >= budgetMillis
                                || spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations();
                    }
                    if (complete || budgetOut) {
                        stop.set(true);
                        break;
                    }
                    // pinned sessions revalidate run_id on every reconnect, and completion
                    // verifies the fingerprint: a full topology probe every 5s just stalled the loop
                    if (System.nanoTime() - lastTopology >= 30_000_000_000L) {
                        if (!topology.fingerprint()
                                .equals(redis.topology(spec.clusterId(), spec.database(), deadline).fingerprint())) {
                            drift = "TOPOLOGY_CHANGED";
                            stop.set(true);
                            break;
                        }
                        lastTopology = System.nanoTime();
                    }
                    if (System.nanoTime() - lastSave >= 1_000_000_000L) {
                        DistributionCheckpoint snap;
                        synchronized (lock) {
                            snap = state(topology, cursors, 0, counter, elapsed, estimatedTotal);
                        }
                        if (!persisted) {
                            // the first checkpoint is synchronous: there is no point scanning a
                            // whole budget if the task cannot persist or already drifted
                            if (!repository.save(lease, snap, "RUNNING", null)) {
                                stop.set(true);
                                break;
                            }
                            persisted = true;
                        } else if (saveInFlight.compareAndSet(false, true)) {
                            // later checkpoints are latest-wins on a single writer: if the previous
                            // save is still in flight on a slow link this one is simply skipped
                            checkpoints.execute(() -> {
                                try {
                                    if (!repository.save(lease, snap, "RUNNING", null))
                                        halt.set(true);
                                } catch (RuntimeException e) {
                                    halt.set(true);
                                } finally {
                                    saveInFlight.set(false);
                                }
                            });
                        }
                        committed = snap;
                        lastSave = System.nanoTime();
                    }
                }
            } catch (InterruptedException e) {
                stop.set(true); // pause, cancel or lease loss: stop without writing a terminal state
            }
            stop.set(true);
            shardWorkers.shutdownNow();
            try {
                shardWorkers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.interrupted();
            }
            if (failure.get() != null)
                throw new IllegalStateException(failure.get());
            if (drift != null) {
                repository.save(lease, committed, "INCOMPLETE", "TOPOLOGY_CHANGED");
                return;
            }
            long finalElapsed = initialElapsed + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            synchronized (lock) {
                complete = cursors.stream().allMatch(DistributionCheckpoint.Cursor::completed);
                budgetOut = finalElapsed >= budgetMillis
                        || spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations();
            }
            if (complete) {
                if (!topology.fingerprint()
                        .equals(redis.topology(spec.clusterId(), spec.database(), deadline).fingerprint())) {
                    repository.save(lease, committed, "INCOMPLETE", "TOPOLOGY_CHANGED");
                    return;
                }
                DistributionCheckpoint snap;
                synchronized (lock) {
                    snap = state(topology, cursors, 0, counter, finalElapsed, estimatedTotal);
                }
                repository.save(lease, snap, "COMPLETED", null);
                return;
            }
            if (budgetOut) {
                DistributionCheckpoint snap;
                synchronized (lock) {
                    snap = state(topology, cursors, 0, counter, finalElapsed, estimatedTotal);
                }
                repository.save(lease, snap, "INCOMPLETE", "BUDGET_REACHED");
                return;
            }
            // paused, cancelled, lease lost or checkpoint drift: status already moved elsewhere
        } catch (RuntimeException e) {
            String reason = e.getMessage();
            if (reason == null || !reason.matches("(?:SCAN|DISTRIBUTION|INVALID_DISTRIBUTION)_[A-Z_]{1,40}"))
                reason = "SCAN_FAILED";
            // the client only ever sees the mapped code; this line is where the real cause lives
            log.warn("distribution task {} ended as {}: {}", lease.taskId(), reason, e.toString());
            // a claim that never opened its first connection made no progress: put the task
            // back in the queue instead of failing it, so an instance that can reach the
            // cluster takes over (instances share one database and one claim sweep)
            if (!persisted && !lost.get()
                    && ("SCAN_CONNECTION_FAILED".equals(reason) || "SCAN_TIMEOUT".equals(reason)))
                try {
                    repository.requeue(lease, reason);
                    requeued = true;
                } catch (RuntimeException ignored) {
                    /* fall through to the terminal failure below */}
            if (!requeued) {
                String status = reason.contains("LIMIT")
                        ? "INCOMPLETE"
                        : reason.equals("SCAN_TIMEOUT")
                                ? "PAUSED"
                                : reason.equals("SCAN_TOPOLOGY_UNSTABLE") ? "INCOMPLETE" : "FAILED";
                try {
                    if (!lost.get())
                        repository.save(lease, committed, status, reason);
                } catch (RuntimeException ignored) {
                    /* No further scan or buffered accumulation after persistence failure. */}
            }
        } finally {
            if (shardWorkers != null)
                shardWorkers.shutdownNow(); // workers close their own sessions
            running.remove(lease.taskId(), executionThread);
            Thread.interrupted();
            heartbeat.cancel(false);
            // a requeued claim keeps its lease as the cooldown; releasing here would lift it
            if (!requeued)
                try {
                    repository.release(lease);
                } catch (RuntimeException ignored) {
                    /* lease expires */}
        }
    }
    private static DistributionCheckpoint state(DistributionScanPort.Topology topology,
            List<DistributionCheckpoint.Cursor> cursors, int next, DistributionCounter counter, long elapsed,
            long estimatedTotal) {
        return new DistributionCheckpoint(topology.fingerprint(), cursors, next, counter.observed(), elapsed,
                estimatedTotal, counter.snapshot());
    }
    private static void sleepUntil(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            return;
        try {
            TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SCAN_INTERRUPTED");
        }
    }
    @Override
    public void destroy() {
        closing = true;
        executor.shutdownNow();
        heartbeats.shutdownNow();
        checkpoints.shutdownNow();
    }
}
