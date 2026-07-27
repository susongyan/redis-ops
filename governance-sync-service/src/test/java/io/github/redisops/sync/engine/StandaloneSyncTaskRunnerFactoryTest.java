package io.github.redisops.sync.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StandaloneSyncTaskRunnerFactoryTest {

    @Test
    void rejectsInvalidFullApplyConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> factory(0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> factory(65, 100, 1));
    }

    @Test
    void rejectsQueueSmallerThanConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> factory(4, 3, 1));
    }

    @Test
    void rejectsInvalidPipelineSize() {
        assertThrows(IllegalArgumentException.class, () -> factory(4, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> factory(4, 10, 10_001));
    }

    private static StandaloneSyncTaskRunnerFactory factory(int concurrency, int queueCapacity,
            int pipelineSize) {
        return new StandaloneSyncTaskRunnerFactory(null, null, null, null, null,
                Path.of("data"), 1024, 1000, concurrency, queueCapacity, pipelineSize,
                4 * 1024 * 1024L, 2000, 1000);
    }
}
