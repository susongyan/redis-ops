package io.github.susongyan.redisops.platform.application.distribution;

import io.github.susongyan.redisops.platform.common.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.distribution.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.*;
import java.nio.charset.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;

@Service
public class DistributionService implements DisposableBean {
    private final DistributionRepository repository;
    private final DistributionScanPort redis;
    private final RedisConnectionProfileProvider profiles;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1,
            r -> daemon(r, "distribution-heartbeat"));
    private final int maxDuration, maxScanCount, maxGroups;
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
            @Value("${distribution.max-groups:1000}") int maxGroups) {
        if (concurrent < 1 || concurrent > 2 || maxDuration < 1 || maxDuration > 21600 || maxScanCount < 1
                || maxScanCount > 5000
                || maxGroups < 1 || maxGroups > 1000)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CONFIGURATION");
        this.repository = repository;
        this.redis = redis;
        this.profiles = profiles;
        this.maxDuration = maxDuration;
        this.maxScanCount = maxScanCount;
        this.maxGroups = maxGroups;
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
        return repository.create(spec);
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
        return repository.get(id);
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
        return repository.groups(id, page, size);
    }
    public DistributionTask control(long id, long version, String action) {
        var result = repository.control(id, version, action);
        if (!"resume".equals(action)) {
            Thread thread = running.get(id);
            if (thread != null)
                thread.interrupt();
        }
        return result;
    }
    public void cleanup() {
        repository.cleanup(30);
    }
    public record Preview(List<String> samples, long observed, long oversized, long binary, boolean limited,
            int totalShards, int visitedShards) {
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
                    visited);
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
                    execute(lease.get());
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }
    private void execute(DistributionRepository.Lease lease) {
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
        long started = System.nanoTime();
        try {
            var task = repository.get(lease.taskId());
            var spec = task.spec();
            if (spec.legacyRateConfiguration()) {
                repository.save(lease, null, "INCOMPLETE", "LEGACY_RATE_CONFIGURATION");
                return;
            }
            if (spec.scanCount() > maxScanCount || spec.scanIntervalMillis() < minScanIntervalMillis) {
                repository.save(lease, null, "PAUSED", "DEPLOYMENT_BUDGET_CHANGED");
                return;
            }
            committed = repository.checkpoint(task.id()).orElse(null);
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
            var classifier = new DistributionClassifier(spec.rules());
            var counter = new DistributionCounter(spec.mode(), spec.capacity(), spec.rules());
            List<DistributionCheckpoint.Cursor> cursors = new ArrayList<>();
            int next = 0, interval = spec.scanIntervalMillis(), slow = 0;
            long initialElapsed = 0;
            if (committed == null)
                for (var shard : topology.shards())
                    cursors.add(new DistributionCheckpoint.Cursor(shard, "0", false));
            else {
                counter.restore(committed.observed(), committed.entries());
                cursors.addAll(committed.cursors());
                next = committed.nextShard();
                initialElapsed = committed.elapsedMillis();
                interval = Math.max(interval, committed.effectiveIntervalMillis());
                slow = committed.slowStreak();
            }
            long lastSave = 0, lastTopology = System.nanoTime();
            int timeouts = 0;
            while (!closing && !lost.get()) {
                if (!"RUNNING".equals(repository.get(task.id()).status()))
                    return;
                long elapsed = initialElapsed + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                boolean complete = cursors.stream().allMatch(DistributionCheckpoint.Cursor::completed);
                boolean budget = elapsed >= Math.min(spec.durationSeconds(), maxDuration) * 1000L
                        || spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations();
                if (complete || budget) {
                    if (complete && !topology.fingerprint()
                            .equals(redis.topology(spec.clusterId(), spec.database(), deadline).fingerprint())) {
                        repository.save(lease, committed, "INCOMPLETE", "TOPOLOGY_CHANGED");
                        return;
                    }
                    var state = state(topology, cursors, next, counter, elapsed, interval, slow);
                    repository.save(lease, state, complete ? "COMPLETED" : "INCOMPLETE",
                            complete ? null : "BUDGET_REACHED");
                    return;
                }
                if (System.nanoTime() - lastTopology >= 5_000_000_000L) {
                    if (!topology.fingerprint()
                            .equals(redis.topology(spec.clusterId(), spec.database(), deadline).fingerprint())) {
                        repository.save(lease, committed, "INCOMPLETE", "TOPOLOGY_CHANGED");
                        return;
                    }
                    lastTopology = System.nanoTime();
                }
                var cursor = cursors.get(next);
                if (cursor.completed()) {
                    next = (next + 1) % cursors.size();
                    continue;
                }
                long before = System.nanoTime();
                DistributionScanPort.Page batch;
                try {
                    batch = redis.scan(spec.clusterId(), spec.database(), cursor.shard(), cursor.cursor(),
                            spec.scanCount(),
                            deadline);
                    timeouts = 0;
                } catch (IllegalStateException e) {
                    if ("SCAN_TIMEOUT".equals(e.getMessage()) && ++timeouts < 3)
                        continue;
                    throw e;
                }
                if (lost.get())
                    return;
                if (System.nanoTime() - before > 200_000_000L)
                    slow++;
                else
                    slow = 0;
                if (slow == 3)
                    interval = Math.min(60000, interval * 2);
                if (slow >= 6) {
                    repository.save(lease, committed, "PAUSED", "SCAN_SLOW");
                    return;
                }
                int accepted = 0;
                for (byte[] key : batch.keys()) {
                    if (spec.maxObservations() > 0 && counter.observed() >= spec.maxObservations())
                        break;
                    counter.accept(classifier.classify(key));
                    accepted++;
                }
                cursors.set(next, new DistributionCheckpoint.Cursor(cursor.shard(), batch.cursor(),
                        accepted == batch.keys().size() && "0".equals(batch.cursor())));
                next = (next + 1) % cursors.size();
                if (System.nanoTime() - lastSave >= 1_000_000_000L) {
                    var state = state(topology, cursors, next, counter,
                            initialElapsed + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), interval,
                            slow);
                    if (!repository.save(lease, state, "RUNNING", null))
                        return;
                    committed = state;
                    lastSave = System.nanoTime();
                }
                long waitUntil = Math.min(
                        before + interval * 1_000_000L,
                        started + Math.max(0, Math.min(spec.durationSeconds(), maxDuration) * 1000L - initialElapsed)
                                * 1_000_000L);
                while (!closing && !lost.get() && System.nanoTime() < waitUntil)
                    sleepUntil(Math.min(waitUntil, System.nanoTime() + 200_000_000L));
            }
        } catch (RuntimeException e) {
            String reason = e.getMessage();
            if (reason == null || !reason.matches("(?:SCAN|DISTRIBUTION|INVALID_DISTRIBUTION)_[A-Z_]{1,40}"))
                reason = "SCAN_FAILED";
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
        } finally {
            running.remove(lease.taskId(), executionThread);
            Thread.interrupted();
            heartbeat.cancel(false);
            try {
                repository.release(lease);
            } catch (RuntimeException ignored) {
                /* lease expires */}
        }
    }
    private static DistributionCheckpoint state(DistributionScanPort.Topology topology,
            List<DistributionCheckpoint.Cursor> cursors, int next, DistributionCounter counter, long elapsed,
            int interval,
            int slow) {
        return new DistributionCheckpoint(topology.fingerprint(), cursors, next, counter.observed(), elapsed,
                counter.snapshot(), interval, slow);
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
    }
}
