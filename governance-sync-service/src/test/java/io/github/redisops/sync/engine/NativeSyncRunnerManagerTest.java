package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeSyncRunnerManagerTest {

    @Test
    void managesRunnerLifecycleAndRuntimeLease() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        when(sync.renewRuntime(anyLong(), anyString(), anyLong(), anyString(), anyLong())).thenReturn(true);
        FakeRunner runner = new FakeRunner();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync, (task, recovery) -> runner, 1);
        SyncTask task = task(1);

        manager.prepare(task, "worker-1", 30, false);
        manager.start(task.id());
        manager.pause(task.id());
        manager.resume(task, "worker-1", 30);
        manager.updateLimits(task);
        manager.renewLeases();
        manager.finish(task.id());

        assertEquals(1, runner.prepared.get());
        assertEquals(1, runner.started.get());
        assertEquals(1, runner.paused.get());
        assertEquals(1, runner.resumed.get());
        assertEquals(1, runner.limitUpdates.get());
        assertEquals(1, runner.finished.get());
        assertTrue(runner.closed.get());
        assertEquals(0, manager.activeCount());
        verify(sync).claimRuntime(eq(1L), anyString(), eq("worker-1"), eq(30L));
        verify(sync, atLeast(5)).renewRuntime(eq(1L), eq("worker-1"), eq(30L), anyString(), eq(12L));
        verify(sync).releaseRuntime(1L, "worker-1", "FINISHED", null);
    }

    @Test
    void enforcesWorkerConcurrencyLimit() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        FakeRunner first = new FakeRunner();
        FakeRunner second = new FakeRunner();
        AtomicInteger created = new AtomicInteger();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync,
                (task, recovery) -> created.getAndIncrement() == 0 ? first : second, 1);

        manager.prepare(task(1), "worker", 30, false);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager.prepare(task(2), "worker", 30, false));

        assertEquals("sync worker concurrency limit reached", error.getMessage());
        assertEquals(1, created.get());
        manager.cancel(1);
        manager.prepare(task(2), "worker", 30, false);
        assertEquals(2, created.get());
        manager.cancel(2);
    }

    @Test
    void stopsRunnerImmediatelyWhenLeaseRenewalFails() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        when(sync.renewRuntime(anyLong(), anyString(), anyLong(), anyString(), anyLong())).thenReturn(false);
        FakeRunner runner = new FakeRunner();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync, (task, recovery) -> runner, 1);

        manager.prepare(task(1), "worker", 30, false);
        manager.renewLeases();

        assertEquals(1, runner.cancelled.get());
        assertTrue(runner.closed.get());
        assertEquals(0, manager.activeCount());
        verify(sync, never()).releaseRuntime(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void startFailsClosedWhenImmediateLeaseRenewalFails() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        when(sync.renewRuntime(anyLong(), anyString(), anyLong(), anyString(), anyLong())).thenReturn(false);
        FakeRunner runner = new FakeRunner();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync, (task, recovery) -> runner, 1);
        manager.prepare(task(1), "worker", 30, false);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> manager.start(1));

        assertEquals("sync runtime lease was lost", error.getMessage());
        assertEquals(1, runner.cancelled.get());
        assertTrue(runner.closed.get());
        assertEquals(0, manager.activeCount());
    }

    @Test
    void releasesCapacityWhenRunnerPreparationFails() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        FakeRunner broken = new FakeRunner();
        broken.prepareError = new IllegalStateException("cannot connect source");
        FakeRunner healthy = new FakeRunner();
        AtomicInteger created = new AtomicInteger();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync,
                (task, recovery) -> created.getAndIncrement() == 0 ? broken : healthy, 1);

        assertThrows(IllegalStateException.class, () -> manager.prepare(task(1), "worker", 30, false));
        manager.prepare(task(2), "worker", 30, false);

        assertTrue(broken.closed.get());
        assertEquals(1, manager.activeCount());
        verify(sync, times(1)).claimRuntime(anyLong(), anyString(), anyString(), anyLong());
        manager.cancel(2);
    }

    @Test
    void recreatesRunnerInRecoveryModeAfterProcessRestart() {
        SyncRepository sync = mock(SyncRepository.class);
        stubRuntimeClaim(sync);
        when(sync.renewRuntime(anyLong(), anyString(), anyLong(), anyString(), anyLong())).thenReturn(true);
        AtomicBoolean recovery = new AtomicBoolean();
        FakeRunner runner = new FakeRunner();
        NativeSyncRunnerManager manager = new NativeSyncRunnerManager(sync, (task, recovering) -> {
            recovery.set(recovering);
            return runner;
        }, 1);

        manager.resume(task(1), "replacement-worker", 30);

        assertTrue(recovery.get());
        assertEquals(1, runner.prepared.get());
        assertEquals(1, runner.resumed.get());
        manager.cancel(1);
    }

    private static SyncTask task(long id) {
        Instant now = Instant.now();
        return new SyncTask(id, "SYNC-" + id, null, 11, 22, SyncPurpose.ADHOC,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.STARTING, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]",
                50_000, 100 * 1024 * 1024L, 50L * 1024 * 1024 * 1024, 4, 100,
                SyncAction.START.name(), true,
                "test fence", null, "epoch-1", null, null, 0, now, now, null);
    }

    private static void stubRuntimeClaim(SyncRepository sync) {
        AtomicReference<String> runtimeId = new AtomicReference<>();
        AtomicReference<String> owner = new AtomicReference<>();
        when(sync.claimRuntime(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            runtimeId.set(invocation.getArgument(1));
            owner.set(invocation.getArgument(2));
            return true;
        });
        when(sync.findRuntime(anyLong())).thenAnswer(invocation -> {
            long taskId = invocation.getArgument(0);
            Instant now = Instant.now();
            return Optional.of(new SyncRuntime(
                    taskId,
                    runtimeId.get(),
                    owner.get(),
                    now.plusSeconds(30),
                    1,
                    "PREPARING",
                    now,
                    0,
                    null,
                    null,
                    0,
                    null,
                    null,
                    now,
                    now));
        });
    }

    private static final class FakeRunner implements SyncTaskRunner {
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger paused = new AtomicInteger();
        private final AtomicInteger resumed = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final AtomicInteger limitUpdates = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private RuntimeException prepareError;
        private String phase = "PREPARED";

        @Override
        public void prepare() {
            prepared.incrementAndGet();
            if (prepareError != null)
                throw prepareError;
        }

        @Override
        public void start() {
            started.incrementAndGet();
            phase = "FULL_RECEIVING";
        }

        @Override
        public void pause() {
            paused.incrementAndGet();
            phase = "PAUSED";
        }

        @Override
        public void resume() {
            resumed.incrementAndGet();
            phase = "INCREMENTAL";
        }

        @Override
        public void finish() {
            finished.incrementAndGet();
            phase = "FINISHED";
        }

        @Override
        public void cancel() {
            cancelled.incrementAndGet();
            phase = "CANCELLED";
        }

        @Override
        public void updateLimits(SyncTask task) {
            limitUpdates.incrementAndGet();
        }

        @Override
        public String phase() {
            return phase;
        }

        @Override
        public long spoolBytes() {
            return 12;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
