package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerAssetReadPort;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveVersionPrecheckTest {
    private final WorkerAssetReadPort assets = mock(WorkerAssetReadPort.class);
    private final RedisDataEndpointResolver endpoints = mock(RedisDataEndpointResolver.class);
    private final RedisEndpoint source = new RedisEndpoint("source", 6379);
    private final RedisEndpoint extra = new RedisEndpoint("extra", 6379);
    private final RedisEndpoint target = new RedisEndpoint("target", 6379);

    private SyncPrecheckExecutor fixture(WorkerClusterMode mode) throws Exception {
        WorkerRedisConnectionProfilePort profiles = id -> new WorkerRedisConnectionProfile(id,
                id == 1 ? mode : WorkerClusterMode.STANDALONE, List.of("seed:6379"), "master", null, "NONE", null);
        when(endpoints.resolvePrimary(any())).thenAnswer(
                call -> ((WorkerRedisConnectionProfile) call.getArgument(0)).clusterId() == 1 ? source : target);
        when(endpoints.resolveClusterMasters(any())).thenReturn(List.of(
                new RedisDataEndpointResolver.ClusterMaster(source, "one", 0, 4000),
                new RedisDataEndpointResolver.ClusterMaster(source, "one", 4001, 8000),
                new RedisDataEndpointResolver.ClusterMaster(extra, "two", 8001, 16383)));
        when(endpoints.readServerVersion(any(), eq(source))).thenReturn("5.0.14");
        when(endpoints.readServerVersion(any(), eq(extra))).thenReturn("6.2.6");
        when(endpoints.readServerVersion(any(), eq(target))).thenReturn("7.4.2");
        return new SyncPrecheckExecutor(assets, profiles, null, null, new ObjectMapper(), endpoints, Path.of("data"),
                1024);
    }

    private String check(SyncPrecheckExecutor executor, Long relation) throws Exception {
        Instant now = Instant.now();
        var task = new WorkerSyncTask(1L, "SYNC", relation, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL",
                SyncContractStatus.STARTING, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]", "{\"policyVersion\":\"v3\"}",
                50000, 100000000, 1048576, 4, 8, "START", true, "test", null, "epoch", null, null, 0, now, now, null);
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("compatibleVersions", WorkerSyncTask.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, task);
    }

    @Test
    void clusterChecksAllDistinctMastersWithoutReadingAssetVersion() throws Exception {
        var executor = fixture(WorkerClusterMode.CLUSTER);
        assertTrue(check(executor, null).contains("source=[5.0.14, 6.2.6]"));
        verify(endpoints, times(1)).readServerVersion(any(), eq(source));
        verify(endpoints, times(1)).readServerVersion(any(), eq(extra));
        verify(endpoints, times(1)).readServerVersion(any(), eq(target));
        verifyNoInteractions(assets);
        when(endpoints.readServerVersion(any(), eq(extra))).thenReturn("7.5.0");
        var error = assertThrows(InvocationTargetException.class, () -> check(executor, null));
        assertTrue(error.getCause().getMessage().contains("newer Redis"));
    }

    @Test
    void sentinelUsesResolvedPrimaryAndDisasterRecoveryRequiresMatchingVersions() throws Exception {
        var executor = fixture(WorkerClusterMode.SENTINEL);
        assertTrue(check(executor, null).contains("5.0.14"));
        verify(endpoints, times(2)).resolvePrimary(any());
        assertThrows(InvocationTargetException.class, () -> check(executor, 3L));
        when(endpoints.readServerVersion(any(), eq(target))).thenReturn("5.0.4");
        assertTrue(check(executor, 3L).contains("5.0.4"));
    }

    @Test
    void nodeFailureDoesNotFallBackToMetadataOrLeakServerError() throws Exception {
        var executor = fixture(WorkerClusterMode.CLUSTER);
        when(endpoints.readServerVersion(any(), eq(extra))).thenThrow(new java.io.IOException("private-response"));
        var error = assertThrows(InvocationTargetException.class, () -> check(executor, null));
        assertTrue(error.getCause().getMessage().contains("source node extra:6379"));
        assertFalse(error.getCause().getMessage().contains("private-response"));
        verifyNoInteractions(assets);
    }

    @Test
    void rejectsEmptyClusterTopology() throws Exception {
        var executor = fixture(WorkerClusterMode.CLUSTER);
        when(endpoints.resolveClusterMasters(any())).thenReturn(List.of());
        assertThrows(InvocationTargetException.class, () -> check(executor, null));
    }
}
