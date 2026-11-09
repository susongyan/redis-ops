package io.github.susongyan.redisops.worker.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class ClusterFullRestorePool implements AutoCloseable {
    private static final Item STOP = new Item(null, 0);
    private final ClusterTargetRouter target;
    private final TargetFence fence;
    private final LeaseGuard leaseGuard;
    private final Runnable beforeApply;
    private final Runnable afterApply;
    private final FullRestoreProgressListener progress;
    private final FullRestoreQueueBudget budget;
    private final int pipelineSize;
    private final long transactionMaxBytes;
    private final BlockingQueue<Item> queue;
    private final ExecutorService executor;
    private final List<Future<?>> workers;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    ClusterFullRestorePool(ClusterTargetRouter target, TargetFence fence, LeaseGuard leaseGuard, int concurrency,
            int queueCapacity, long queueMaxBytes, int pipelineSize, long transactionMaxBytes,
            Runnable beforeApply, Runnable afterApply, FullRestoreProgressListener progress) {
        if (pipelineSize < 1 || pipelineSize > 10_000)
            throw new IllegalArgumentException("full restore pipeline size must be within [1, 10000]");
        if (transactionMaxBytes < 1024)
            throw new IllegalArgumentException("full restore transaction max bytes must be at least 1024");
        this.target = target;
        this.fence = fence;
        this.leaseGuard = leaseGuard;
        this.beforeApply = beforeApply;
        this.afterApply = afterApply;
        this.progress = progress;
        this.pipelineSize = pipelineSize;
        this.transactionMaxBytes = transactionMaxBytes;
        this.budget = new FullRestoreQueueBudget(queueMaxBytes);
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
        int permits = budget.permits(request);
        try {
            while (!budget.tryAcquire(permits, 100, TimeUnit.MILLISECONDS))
                healthy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while queueing Cluster RESTORE", error);
        }
        Item item = new Item(request, permits);
        try {
            while (!queue.offer(item, 100, TimeUnit.MILLISECONDS))
                healthy();
        } catch (InterruptedException error) {
            budget.release(permits);
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
            // Batching mirrors FullRestorePool: fill up to pipelineSize items (bounded by
            // transactionMaxBytes), send immediately when the queue runs dry or a STOP arrives,
            // and carry the oversized item to the next batch.
            List<TargetCommandSession.RestoreRequest> batch = new ArrayList<>(pipelineSize);
            List<Item> applied = new ArrayList<>(pipelineSize);
            Item carry = null;
            while (!Thread.currentThread().isInterrupted()) {
                Item first = carry == null ? queue.take() : carry;
                carry = null;
                if (first == STOP)
                    return;
                batch.add(first.request());
                applied.add(first);
                long batchBytes = first.request().estimatedBytes();
                boolean stopAfterBatch = false;
                while (batch.size() < pipelineSize) {
                    Item next = queue.poll();
                    if (next == null)
                        break;
                    if (next == STOP) {
                        stopAfterBatch = true;
                        break;
                    }
                    long nextBytes = next.request().estimatedBytes();
                    if (batchBytes + nextBytes > transactionMaxBytes) {
                        carry = next;
                        break;
                    }
                    batch.add(next.request());
                    applied.add(next);
                    batchBytes += nextBytes;
                }
                beforeApply.run();
                try {
                    restoreBatch(batch, lane);
                    progress.applied(lane, batch.size(), batchBytes);
                } finally {
                    afterApply.run();
                    for (Item item : applied)
                        budget.release(item.permits());
                    applied.clear();
                    batch.clear();
                }
                if (stopAfterBatch)
                    return;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    /** Hands the whole batch to the router, which groups it per target node. */
    private void restoreBatch(List<TargetCommandSession.RestoreRequest> batch, int lane) throws IOException {
        target.restore(batch, fence, leaseGuard, lane);
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

    private record Item(TargetCommandSession.RestoreRequest request, int permits) {
    }
}
