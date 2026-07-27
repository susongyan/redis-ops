package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncRepository;
import io.github.redisops.domain.sync.SyncTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

@Component
public class NativeSyncRunnerManager {
    private final SyncRepository sync;
    private final SyncTaskRunnerFactory runners;
    private final Map<Long, ManagedRunner> active = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private final long renewIntervalMillis;
    private ScheduledExecutorService renewScheduler;

    @Autowired
    public NativeSyncRunnerManager(SyncRepository sync, SyncTaskRunnerFactory runners,
            @Value("${sync.engine.max-concurrent-tasks:2}") int maxConcurrentTasks,
            @Value("${sync.engine.lease-renew-interval-ms:5000}") long renewIntervalMillis) {
        if (maxConcurrentTasks < 1)
            throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        if (renewIntervalMillis < 1)
            throw new IllegalArgumentException("renewIntervalMillis must be positive");
        this.sync = sync;
        this.runners = runners;
        this.capacity = new Semaphore(maxConcurrentTasks);
        this.renewIntervalMillis = renewIntervalMillis;
    }

    NativeSyncRunnerManager(SyncRepository sync, SyncTaskRunnerFactory runners, int maxConcurrentTasks) {
        if (maxConcurrentTasks < 1)
            throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        this.sync = sync;
        this.runners = runners;
        this.capacity = new Semaphore(maxConcurrentTasks);
        this.renewIntervalMillis = 0;
    }

    @PostConstruct
    public void startLeaseRenewal() {
        if (renewIntervalMillis == 0)
            return;
        renewScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sync-lease-renewal");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        });
        renewScheduler.scheduleWithFixedDelay(this::renewLeases, renewIntervalMillis,
                renewIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void prepare(SyncTask task, String owner, long leaseSeconds, boolean recovery) {
        if (leaseSeconds < 1)
            throw new IllegalArgumentException("leaseSeconds must be positive");
        ManagedRunner existing = active.get(task.id());
        if (existing != null) {
            if (existing.owner().equals(owner))
                return;
            throw new IllegalStateException("sync task is already managed by another runner");
        }
        if (!capacity.tryAcquire())
            throw new IllegalStateException("sync worker concurrency limit reached");

        SyncTaskRunner runner = null;
        boolean installed = false;
        boolean claimed = false;
        try {
            runner = runners.create(task, recovery);
            runner.prepare();
            String runtimeId = java.util.UUID.randomUUID().toString();
            if (!sync.claimRuntime(task.id(), runtimeId, owner, leaseSeconds))
                throw new IllegalStateException("sync runtime is already leased");
            claimed = true;
            var runtime = sync.findRuntime(task.id())
                    .orElseThrow(() -> new IllegalStateException("claimed sync runtime is missing"));
            if (!owner.equals(runtime.leaseOwner()))
                throw new IllegalStateException("claimed sync runtime owner changed");
            runner.leaseAcquired(runtime);
            ManagedRunner managed = new ManagedRunner(task.id(), owner, leaseSeconds, runner);
            if (active.putIfAbsent(task.id(), managed) != null)
                throw new IllegalStateException("sync task was concurrently prepared");
            installed = true;
        } finally {
            if (!installed) {
                if (claimed)
                    sync.releaseRuntime(task.id(), owner, "FAILED", "runner initialization failed");
                closeQuietly(runner);
                capacity.release();
            }
        }
    }

    public void start(long taskId) {
        ManagedRunner managed = required(taskId);
        try {
            managed.runner().start();
            renewOrLose(managed);
        } catch (RuntimeException error) {
            fail(managed, "FAILED", error);
            throw error;
        }
    }

    public void pause(long taskId) {
        ManagedRunner managed = required(taskId);
        managed.runner().pause();
        renewOrLose(managed);
    }

    public void resume(SyncTask task, String owner, long leaseSeconds) {
        ManagedRunner managed = active.get(task.id());
        if (managed == null) {
            prepare(task, owner, leaseSeconds, true);
            managed = required(task.id());
        }
        resumePrepared(managed);
    }

    public void prepareRecovery(SyncTask task, String owner, long leaseSeconds) {
        prepare(task, owner, leaseSeconds, true);
    }

    public void resumePrepared(long taskId) {
        resumePrepared(required(taskId));
    }

    private void resumePrepared(ManagedRunner managed) {
        try {
            managed.runner().resume();
            renewOrLose(managed);
        } catch (RuntimeException error) {
            fail(managed, "FAILED", error);
            throw error;
        }
    }

    public void finish(long taskId) {
        ManagedRunner managed = required(taskId);
        try {
            managed.runner().finish();
            remove(managed, "FINISHED", null, false);
        } catch (RuntimeException error) {
            fail(managed, "FAILED", error);
            throw error;
        }
    }

    public void cancel(long taskId) {
        ManagedRunner managed = active.get(taskId);
        if (managed == null)
            return;
        try {
            managed.runner().cancel();
        } finally {
            remove(managed, "CANCELLED", null, false);
        }
    }

    public void abort(long taskId, Throwable error) {
        ManagedRunner managed = active.get(taskId);
        if (managed != null)
            remove(managed, "FAILED", safe(error), false);
    }

    public void updateLimits(SyncTask task) {
        ManagedRunner managed = required(task.id());
        managed.runner().updateLimits(task);
        renewOrLose(managed);
    }

    public boolean isManaged(long taskId) {
        return active.containsKey(taskId);
    }

    public int activeCount() {
        return active.size();
    }

    public void renewLeases() {
        for (ManagedRunner managed : new ArrayList<>(active.values())) {
            if (!renew(managed)) {
                try {
                    managed.runner().revokeLease();
                    managed.runner().cancel();
                } finally {
                    remove(managed, "LEASE_LOST", "runtime lease renewal failed", true);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (renewScheduler != null)
            renewScheduler.shutdownNow();
        for (ManagedRunner managed : new ArrayList<>(active.values())) {
            try {
                managed.runner().revokeLease();
                managed.runner().cancel();
            } finally {
                remove(managed, "SHUTDOWN", null, false);
            }
        }
    }

    private boolean renew(ManagedRunner managed) {
        try {
            managed.runner().assertLeaseValid();
            boolean renewed = sync.renewRuntime(managed.taskId(), managed.owner(), managed.leaseSeconds(),
                    managed.runner().phase(), managed.runner().spoolBytes());
            if (!renewed) {
                managed.runner().revokeLease();
                return false;
            }
            managed.runner().leaseRenewed(Duration.ofSeconds(managed.leaseSeconds()));
            return true;
        } catch (LeaseGuard.LeaseLostException error) {
            managed.runner().revokeLease();
            sync.releaseRuntime(managed.taskId(), managed.owner(), "LEASE_LOST", safe(error));
            return false;
        }
    }

    private void renewOrLose(ManagedRunner managed) {
        if (renew(managed))
            return;
        try {
            managed.runner().cancel();
        } finally {
            remove(managed, "LEASE_LOST", "runtime lease renewal failed", true);
        }
        throw new IllegalStateException("sync runtime lease was lost");
    }

    private ManagedRunner required(long taskId) {
        ManagedRunner managed = active.get(taskId);
        if (managed == null)
            throw new IllegalStateException("sync task has no runner on this instance");
        return managed;
    }

    private void fail(ManagedRunner managed, String phase, RuntimeException error) {
        remove(managed, phase, safe(error), false);
    }

    private void remove(ManagedRunner managed, String phase, String error, boolean leaseAlreadyLost) {
        if (!active.remove(managed.taskId(), managed))
            return;
        try {
            managed.runner().revokeLease();
            if (!leaseAlreadyLost)
                sync.releaseRuntime(managed.taskId(), managed.owner(), phase, error);
        } finally {
            closeQuietly(managed.runner());
            capacity.release();
        }
    }

    private static void closeQuietly(SyncTaskRunner runner) {
        if (runner == null)
            return;
        try {
            runner.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not hide the original lifecycle result.
        }
    }

    private static String safe(Throwable error) {
        String value = error.getMessage();
        if (value == null)
            value = error.getClass().getSimpleName();
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private record ManagedRunner(long taskId, String owner, long leaseSeconds, SyncTaskRunner runner) {
    }
}
