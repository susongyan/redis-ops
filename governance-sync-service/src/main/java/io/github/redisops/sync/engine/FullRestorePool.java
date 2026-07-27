package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.RespProtocolException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class FullRestorePool implements AutoCloseable {
    private static final RestoreItem STOP = new RestoreItem(null);

    private final RedisConnectionProfile profile;
    private final RedisDataEndpointResolver endpoints;
    private final int database;
    private final long taskId;
    private final String channel;
    private final Duration connectTimeout;
    private final int pipelineSize;
    private final long transactionMaxBytes;
    private final TargetFence fence;
    private final LeaseGuard leaseGuard;
    private final Runnable beforeApply;
    private final Runnable afterApply;
    private final BlockingQueue<RestoreItem> queue;
    private final ExecutorService executor;
    private final List<Future<?>> workers;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    FullRestorePool(RedisConnectionProfile profile, RedisDataEndpointResolver endpoints, int database,
            long taskId, Duration connectTimeout,
            int concurrency, int queueCapacity, int pipelineSize, long transactionMaxBytes,
            TargetFence fence, LeaseGuard leaseGuard, Runnable beforeApply, Runnable afterApply) {
        this(profile, endpoints, database, taskId, "standalone", connectTimeout, concurrency, queueCapacity,
                pipelineSize, transactionMaxBytes, fence, leaseGuard, beforeApply, afterApply);
    }

    FullRestorePool(RedisConnectionProfile profile, RedisDataEndpointResolver endpoints, int database,
            long taskId, String channel, Duration connectTimeout,
            int concurrency, int queueCapacity, int pipelineSize, long transactionMaxBytes,
            TargetFence fence, LeaseGuard leaseGuard, Runnable beforeApply, Runnable afterApply) {
        if (concurrency < 1 || concurrency > 64)
            throw new IllegalArgumentException("full restore concurrency must be between 1 and 64");
        if (queueCapacity < concurrency)
            throw new IllegalArgumentException("full restore queue capacity must be at least the concurrency");
        if (pipelineSize < 1 || pipelineSize > 10_000)
            throw new IllegalArgumentException("full restore pipeline size must be between 1 and 10000");
        if (transactionMaxBytes < 1024)
            throw new IllegalArgumentException("full restore transaction max bytes must be at least 1024");
        this.profile = profile;
        this.endpoints = endpoints;
        this.database = database;
        this.taskId = taskId;
        this.channel = channel;
        this.connectTimeout = connectTimeout;
        this.pipelineSize = pipelineSize;
        this.transactionMaxBytes = transactionMaxBytes;
        this.fence = fence;
        this.leaseGuard = leaseGuard;
        this.beforeApply = beforeApply;
        this.afterApply = afterApply;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "redis-full-restore-" + taskId);
            thread.setDaemon(true);
            return thread;
        });
        this.workers = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            int lane = i;
            workers.add(executor.submit(() -> runWorker(lane)));
        }
    }

    void submit(TargetCommandSession.RestoreRequest request) throws IOException {
        ensureHealthy();
        try {
            while (!queue.offer(new RestoreItem(request), 100, TimeUnit.MILLISECONDS))
                ensureHealthy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while applying full RDB", error);
        }
        ensureHealthy();
    }

    void awaitCompletion() throws IOException {
        if (!completed.compareAndSet(false, true))
            throw new IllegalStateException("full restore pool was already completed");
        try {
            for (int i = 0; i < workers.size(); i++) {
                while (!queue.offer(STOP, 100, TimeUnit.MILLISECONDS))
                    ensureHealthy();
            }
            for (Future<?> worker : workers)
                worker.get();
            ensureHealthy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for full RDB apply", error);
        } catch (ExecutionException error) {
            failure.compareAndSet(null, error.getCause());
            ensureHealthy();
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runWorker(int lane) {
        try {
            List<TargetCommandSession.RestoreRequest> batch = new ArrayList<>(pipelineSize);
            RestoreItem carry = null;
            while (!Thread.currentThread().isInterrupted()) {
                RestoreItem first = carry == null ? queue.take() : carry;
                carry = null;
                if (first == STOP)
                    return;
                batch.add(first.request());
                long batchBytes = estimatedBytes(first.request());
                boolean stopAfterBatch = false;
                while (batch.size() < pipelineSize) {
                    RestoreItem next = queue.poll();
                    if (next == null)
                        break;
                    if (next == STOP) {
                        stopAfterBatch = true;
                        break;
                    }
                    long nextBytes = estimatedBytes(next.request());
                    if (!batch.isEmpty() && batchBytes + nextBytes > transactionMaxBytes) {
                        carry = next;
                        break;
                    }
                    batch.add(next.request());
                    batchBytes += nextBytes;
                }
                beforeApply.run();
                try {
                    restoreBatch(batch, lane);
                } finally {
                    afterApply.run();
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

    private void restoreBatch(List<TargetCommandSession.RestoreRequest> batch, int lane) throws IOException {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            leaseGuard.assertValid();
            RedisEndpoint endpoint = endpoints.resolvePrimary(profile);
            try (TargetCommandSession session = new TargetCommandSession(profile, endpoint, database,
                    taskId, connectTimeout, channel)) {
                session.restoreBatch(batch, fence, lane, leaseGuard);
                return;
            } catch (IOException | RespProtocolException error) {
                last = error;
            }
        }
        throw new IOException("target master remained unavailable during full restore", last);
    }

    private void ensureHealthy() throws IOException {
        Throwable error = failure.get();
        if (error == null)
            return;
        if (error instanceof IOException io)
            throw io;
        if (error instanceof RuntimeException runtime)
            throw runtime;
        throw new IOException("full RDB apply failed", error);
    }

    private static long estimatedBytes(TargetCommandSession.RestoreRequest request) {
        return request.key().length + request.payload().length + 128L;
    }

    private record RestoreItem(TargetCommandSession.RestoreRequest request) {
    }
}
