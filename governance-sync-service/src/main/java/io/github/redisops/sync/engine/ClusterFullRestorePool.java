package io.github.redisops.sync.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class ClusterFullRestorePool implements AutoCloseable {
    private static final Item STOP = new Item(null);
    private final ClusterTargetRouter target;
    private final TargetFence fence;
    private final LeaseGuard leaseGuard;
    private final Runnable beforeApply;
    private final Runnable afterApply;
    private final BlockingQueue<Item> queue;
    private final ExecutorService executor;
    private final List<Future<?>> workers;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    ClusterFullRestorePool(ClusterTargetRouter target, TargetFence fence, LeaseGuard leaseGuard, int concurrency,
            int queueCapacity, Runnable beforeApply, Runnable afterApply) {
        this.target = target;
        this.fence = fence;
        this.leaseGuard = leaseGuard;
        this.beforeApply = beforeApply;
        this.afterApply = afterApply;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "redis-cluster-full-restore");
            thread.setDaemon(true);
            return thread;
        });
        this.workers = new ArrayList<>(concurrency);
        for (int lane = 0; lane < concurrency; lane++) {
            int currentLane = lane;
            workers.add(executor.submit(() -> run(currentLane)));
        }
    }

    void submit(TargetCommandSession.RestoreRequest request) throws IOException {
        healthy();
        try {
            while (!queue.offer(new Item(request), 100, TimeUnit.MILLISECONDS))
                healthy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while queueing Cluster RESTORE", error);
        }
    }

    void awaitCompletion() throws IOException {
        try {
            for (int i = 0; i < workers.size(); i++)
                while (!queue.offer(STOP, 100, TimeUnit.MILLISECONDS))
                    healthy();
            for (Future<?> worker : workers)
                worker.get();
            healthy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while draining Cluster RESTORE", error);
        } catch (ExecutionException error) {
            failure.compareAndSet(null, error.getCause());
            healthy();
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void run(int lane) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Item item = queue.take();
                if (item == STOP)
                    return;
                beforeApply.run();
                try {
                    target.restore(item.request(), fence, leaseGuard, lane);
                } finally {
                    afterApply.run();
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private void healthy() throws IOException {
        Throwable error = failure.get();
        if (error == null)
            return;
        if (error instanceof IOException io)
            throw io;
        if (error instanceof RuntimeException runtime)
            throw runtime;
        throw new IOException("Cluster full restore failed", error);
    }

    private record Item(TargetCommandSession.RestoreRequest request) {
    }
}
