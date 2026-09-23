package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandaloneSyncTaskRunnerFactoryTest {
    @Test
    void redis5RuntimeVersionGateAdmitsV3ButNotV2() throws Exception {
        String endpoint = System.getenv("SYNC_TRANSACTION_TEST_REDIS5");
        org.junit.jupiter.api.Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank());
        WorkerRedisConnectionProfilePort profiles = id -> new WorkerRedisConnectionProfile(id,
                WorkerClusterMode.STANDALONE, List.of(endpoint), null, null, "NONE", null);
        assertInstanceOf(StandaloneSyncTaskRunner.class,
                factory(profiles, 4, 10, 1).create(task("{\"policyVersion\":\"v3\"}"), false));
        var error = assertThrows(SyncBlockedException.class,
                () -> factory(profiles, 4, 10, 1).create(task("{\"policyVersion\":\"v2\"}"), false));
        org.junit.jupiter.api.Assertions.assertEquals("BLOCKED_UNSUPPORTED_REDIS_VERSION", error.reason());
    }

    @Test
    void precheckUsesPolicyVersionAndStillRejectsDowngrades() throws Exception {
        var assets = org.mockito.Mockito
                .mock(io.github.susongyan.redisops.worker.persistence.WorkerAssetReadPort.class);
        WorkerRedisConnectionProfilePort profiles = id -> new WorkerRedisConnectionProfile(id,
                WorkerClusterMode.STANDALONE, List.of("127.0.0.1:" + (6378 + id)), null, null, "NONE", null);
        var endpoints = org.mockito.Mockito.mock(RedisDataEndpointResolver.class);
        var source = new RedisEndpoint("127.0.0.1", 6379);
        var target = new RedisEndpoint("127.0.0.1", 6380);
        org.mockito.Mockito.when(endpoints.resolvePrimary(org.mockito.ArgumentMatchers.any())).thenAnswer(
                call -> ((WorkerRedisConnectionProfile) call.getArgument(0)).clusterId() == 1 ? source : target);
        org.mockito.Mockito.when(endpoints.readServerVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(source))).thenReturn("5.0.14");
        org.mockito.Mockito.when(endpoints.readServerVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(target))).thenReturn("5.0.14");
        var precheck = new SyncPrecheckExecutor(assets, profiles, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), endpoints, Path.of("data"), 1024);
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("compatibleVersions", WorkerSyncTask.class);
        method.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertEquals("INFO server: source=[5.0.14] -> target=[5.0.14]",
                method.invoke(precheck, task("{\"policyVersion\":\"v3\"}")));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(precheck, task("{\"policyVersion\":\"v2\"}")));
        org.mockito.Mockito.when(endpoints.readServerVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(target))).thenReturn("7.4.2");
        org.junit.jupiter.api.Assertions.assertEquals("INFO server: source=[5.0.14] -> target=[7.4.2]",
                method.invoke(precheck, task("{\"policyVersion\":\"v3\"}")));
        org.mockito.Mockito.when(endpoints.readServerVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(source))).thenReturn("7.4.2");
        org.mockito.Mockito.when(endpoints.readServerVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(target))).thenReturn("5.0.14");
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(precheck, task("{\"policyVersion\":\"v3\"}")));
    }
    @Test
    void rejectsUnknownPolicyBeforeReadingCredentials() {
        var task = task("{\"policyVersion\":\"v999\"}");
        var profiles = org.mockito.Mockito.mock(WorkerRedisConnectionProfilePort.class);
        var error = assertThrows(SyncBlockedException.class, () -> factory(profiles, 4, 10, 1).create(task, false));
        org.junit.jupiter.api.Assertions.assertEquals("BLOCKED_UNSUPPORTED_COMMAND_POLICY", error.reason());
        org.mockito.Mockito.verifyNoInteractions(profiles);
    }

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
                factory(profiles(WorkerClusterMode.CLUSTER, WorkerClusterMode.STANDALONE), 4, 10, 1)
                        .create(task(), false));
        assertInstanceOf(ClusterSyncTaskRunner.class,
                factory(profiles(WorkerClusterMode.STANDALONE, WorkerClusterMode.CLUSTER), 4, 10, 1)
                        .create(task(), false));
        assertInstanceOf(StandaloneSyncTaskRunner.class,
                factory(profiles(WorkerClusterMode.STANDALONE, WorkerClusterMode.STANDALONE), 4, 10, 1)
                        .create(task(), false));
    }

    private static StandaloneSyncTaskRunnerFactory factory(int concurrency, int queueCapacity,
            int pipelineSize) {
        return factory(null, concurrency, queueCapacity, pipelineSize);
    }

    private static StandaloneSyncTaskRunnerFactory factory(WorkerRedisConnectionProfilePort profiles,
            int concurrency, int queueCapacity, int pipelineSize) {
        return new StandaloneSyncTaskRunnerFactory(profiles, null, null, null,
                new RedisDataEndpointResolver(1000), new com.fasterxml.jackson.databind.ObjectMapper(),
                Path.of("data"), 1024, 1000, concurrency, queueCapacity, pipelineSize,
                4 * 1024 * 1024L, 2000, 1000);
    }

    private static WorkerRedisConnectionProfilePort profiles(WorkerClusterMode source, WorkerClusterMode target) {
        return clusterId -> new WorkerRedisConnectionProfile(clusterId, clusterId == 1 ? source : target,
                List.of("127.0.0.1:6379"), null, null, "NONE", null);
    }

    private static WorkerSyncTask task() {
        return task("{}");
    }
    private static WorkerSyncTask task(String policy) {
        Instant now = Instant.now();
        return new WorkerSyncTask(1L, "SYNC-1", null, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL",
                SyncContractStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", policy, 50_000, 100_000_000, 1024 * 1024, 4, 8, "START", true, "test",
                null, "epoch", null, null, 0, now, now, null);
    }
}
