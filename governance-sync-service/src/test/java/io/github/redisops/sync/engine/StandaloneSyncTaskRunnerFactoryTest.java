package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void selectsClusterRunnerWhenEitherSideUsesClusterMode() {
        assertInstanceOf(ClusterSyncTaskRunner.class,
                factory(profiles(ClusterMode.CLUSTER, ClusterMode.STANDALONE), 4, 10, 1)
                        .create(task(), false));
        assertInstanceOf(ClusterSyncTaskRunner.class,
                factory(profiles(ClusterMode.STANDALONE, ClusterMode.CLUSTER), 4, 10, 1)
                        .create(task(), false));
        assertInstanceOf(StandaloneSyncTaskRunner.class,
                factory(profiles(ClusterMode.STANDALONE, ClusterMode.STANDALONE), 4, 10, 1)
                        .create(task(), false));
    }

    private static StandaloneSyncTaskRunnerFactory factory(int concurrency, int queueCapacity,
            int pipelineSize) {
        return factory(null, concurrency, queueCapacity, pipelineSize);
    }

    private static StandaloneSyncTaskRunnerFactory factory(RedisConnectionProfileProvider profiles,
            int concurrency, int queueCapacity, int pipelineSize) {
        return new StandaloneSyncTaskRunnerFactory(profiles, null, null, null,
                new RedisDataEndpointResolver(1000), null,
                Path.of("data"), 1024, 1000, concurrency, queueCapacity, pipelineSize,
                4 * 1024 * 1024L, 2000, 1000);
    }

    private static RedisConnectionProfileProvider profiles(ClusterMode source, ClusterMode target) {
        return clusterId -> new RedisConnectionProfile(clusterId, clusterId == 1 ? source : target,
                List.of("127.0.0.1:6379"), null, null, "NONE", null);
    }

    private static SyncTask task() {
        Instant now = Instant.now();
        return new SyncTask(1L, "SYNC-1", null, 1, 2, SyncPurpose.MIGRATION,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", 50_000, 100_000_000, 1024 * 1024, 4, 8, "START", true, "test",
                null, "epoch", null, null, 0, now, now, null);
    }
}
