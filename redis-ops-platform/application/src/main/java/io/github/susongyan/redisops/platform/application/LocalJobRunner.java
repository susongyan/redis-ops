package io.github.susongyan.redisops.platform.application;

import io.github.susongyan.redisops.platform.common.BusinessException;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executes user-triggered long-running jobs (validation, risk scan, discovery) on the instance that
 * received the request, instead of enqueueing them into async_job. These jobs share a bounded pool:
 * they walk millions of keys for minutes to hours, so concurrency must stay low, and a saturated
 * pool rejects the start request (the surrounding transaction rolls the status change back) instead
 * of piling invisible work onto a dead instance. Crash tolerance comes from per-page run heartbeats
 * plus the recovery worker that fails runs whose heartbeat went stale.
 */
@Component
public class LocalJobRunner implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LocalJobRunner.class);
    private final ThreadPoolExecutor executor;

    public LocalJobRunner(@Value("${platform.local-jobs.max-concurrent:2}") int maxConcurrent,
            @Value("${platform.local-jobs.max-queued:16}") int maxQueued) {
        executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxQueued), new CustomizableThreadFactory("local-job-"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    public void submit(String kind, Runnable work) {
        try {
            executor.execute(guarded(kind, work));
        } catch (RejectedExecutionException error) {
            throw new BusinessException(kind + "_BUSY",
                    "this instance already has the maximum number of local jobs queued");
        }
    }

    /**
     * Wraps the work so a stray throw (including Error) never kills the worker thread: the pool
     * replaces a dead thread silently and the task would stay RUNNING until the stale-run sweep.
     */
    private static Runnable guarded(String kind, Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable error) {
                log.error("local {} job failed outside its own error handling", kind, error);
            }
        };
    }

    /**
     * Fails when the pool cannot take more work. Call before persisting a "started" state so the
     * caller's transaction rolls the status change back instead of leaving a run nobody executes.
     */
    public void checkCapacity(String kind) {
        if (executor.getQueue().remainingCapacity() <= 0)
            throw new BusinessException(kind + "_BUSY",
                    "this instance already has the maximum number of local jobs queued");
    }

    /**
     * Submits only after the caller's transaction commits — the pool may start the work on another
     * thread immediately, and that thread must see the committed CHECKING/RUNNING state rather than
     * the pre-start one (which the execute guards would reject).
     */
    public void submitAfterCommit(String kind, Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(kind, work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(kind, work);
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
