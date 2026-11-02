package io.github.susongyan.redisops.worker.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSyncTaskTest {
    @Test
    void nullResetModeDefaultsToPreserve() {
        assertEquals(WorkerSyncTask.RESET_MODE_PRESERVE, task(null).resetMode());
        assertEquals(WorkerSyncTask.RESET_MODE_PRESERVE, task("").resetMode());
        assertEquals(WorkerSyncTask.RESET_MODE_PRESERVE, task("   ").resetMode());
        assertFalse(task(null).flushTarget());
    }

    @Test
    void resetModeIsCaseInsensitive() {
        assertEquals(WorkerSyncTask.RESET_MODE_FLUSH, task(" flush ").resetMode());
        assertTrue(task("flush").flushTarget());
        assertTrue(task("Flush").flushTarget());
    }

    @Test
    void unknownResetModeFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> task("DROP"));
        assertThrows(IllegalArgumentException.class, () -> task("TRUNCATE"));
    }

    private static WorkerSyncTask task(String resetMode) {
        try {
            Constructor<WorkerSyncTask> constructor = WorkerSyncTask.class.getDeclaredConstructor(
                    Long.class, String.class, Long.class, long.class, long.class, String.class, String.class,
                    io.github.susongyan.redisops.sync.contract.SyncContractStatus.class, String.class,
                    int.class, int.class, String.class, String.class, String.class, String.class, long.class,
                    long.class, long.class, int.class, int.class, String.class, boolean.class, String.class,
                    String.class, String.class, Long.class, String.class, long.class,
                    Instant.class, Instant.class, Instant.class);
            constructor.setAccessible(true);
            return constructor.newInstance(1L, "SYNC-1", null, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL",
                    io.github.susongyan.redisops.sync.contract.SyncContractStatus.STARTING, "NATIVE_JAVA",
                    0, 0, "[\"*\"]", "[]", "{}", resetMode, 50_000, 100_000_000, 1024 * 1024, 4, 100, "START",
                    true, null, null, "epoch", null, null, 0, Instant.now(), Instant.now(), null);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
