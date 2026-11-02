package io.github.susongyan.redisops.platform.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalJobRunnerTest {

    @Test
    void strayThrowsIncludingErrorNeverKillTheWorkerThread() throws Exception {
        LocalJobRunner runner = new LocalJobRunner(1, 4);
        try {
            AtomicReference<Thread> worker = new AtomicReference<>();
            CountDownLatch first = new CountDownLatch(1);
            runner.submit("TEST", () -> {
                worker.set(Thread.currentThread());
                first.countDown();
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));

            runner.submit("TEST", () -> {
                throw new IllegalStateException("boom");
            });
            assertRunsOnSameThread(runner, worker);
            runner.submit("TEST", () -> {
                throw new NoClassDefFoundError("boom");
            });
            assertRunsOnSameThread(runner, worker);
        } finally {
            runner.destroy();
        }
    }

    private static void assertRunsOnSameThread(LocalJobRunner runner, AtomicReference<Thread> worker)
            throws InterruptedException {
        AtomicReference<Thread> after = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        runner.submit("TEST", () -> {
            after.set(Thread.currentThread());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertSame(worker.get(), after.get());
    }
}
