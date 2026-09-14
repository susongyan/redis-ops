package io.github.redisops.platform.application.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.redisops.platform.domain.asset.*;
import io.github.redisops.platform.domain.distribution.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class DistributionServiceTest {
    DistributionRepository repository;
    DistributionScanPort redis;
    DistributionService service;
    DistributionRepository.Lease lease = new DistributionRepository.Lease(1, 1, "test", 1);
    DistributionScanPort.Shard shard = new DistributionScanPort.Shard("a", "localhost:6379");
    DistributionScanPort.Topology topology = new DistributionScanPort.Topology("a".repeat(64), List.of(shard));
    DistributionSpec spec = new DistributionSpec(1, 0,
            List.of(new DistributionRule("r", "business", "", DistributionRule.Kind.SEGMENTS, ":", 1)),
            DistributionCounter.Mode.TOP_K, 10, 1000, 60, 200, 200);
    @BeforeEach
    void setup() {
        repository = mock(DistributionRepository.class);
        redis = mock(DistributionScanPort.class);
        when(redis.topology(anyLong(), anyInt(), anyLong())).thenCallRealMethod();
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt(), anyLong())).thenCallRealMethod();
        RedisConnectionProfileProvider profiles = id -> new RedisConnectionProfile(id, ClusterMode.STANDALONE,
                List.of("localhost:6379"), null, null, "NONE", null);
        service = new DistributionService(repository, redis, profiles, 1, 21600, 1000, 1000);
        when(repository.claim(anyString())).thenReturn(Optional.of(lease));
        when(repository.renew(any())).thenReturn(true);
        when(repository.get(1)).thenReturn(new DistributionTask(1, 1, spec, "RUNNING", null, 1, 0, 0, 1, 0,
                Instant.EPOCH, Instant.EPOCH, false));
        when(repository.checkpoint(1)).thenReturn(Optional.empty());
        when(repository.save(any(), any(), anyString(), nullable(String.class))).thenReturn(true);
        when(redis.topology(1, 0)).thenReturn(topology);
    }
    @AfterEach
    void close() {
        service.destroy();
    }
    DistributionScanPort.Page page(String cursor) {
        return new DistributionScanPort.Page(cursor, List.of("order:1".getBytes(StandardCharsets.UTF_8)));
    }
    @Test
    void newBudgetsAreBoundedAndLegacyTasksNeverScan() {
        assertDoesNotThrow(() -> new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 5000, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 5001, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 200, 9));
        var legacy = new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 1000, null, null);
        when(repository.get(1)).thenReturn(new DistributionTask(1, 1, legacy, "RUNNING", null, 1, 0, 0, 1, 0,
                Instant.EPOCH, Instant.EPOCH, false));
        service.poll();
        verify(repository, timeout(3000)).save(lease, null, "INCOMPLETE", "LEGACY_RATE_CONFIGURATION");
        verify(repository, never()).checkpoint(anyLong());
        verify(redis, never()).scan(anyLong(), anyInt(), any(), anyString(), anyInt());
    }
    @Test
    void countAndIntervalApplyAcrossShards() {
        var configured = new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 800, 80);
        when(repository.get(1)).thenReturn(new DistributionTask(1, 1, configured, "RUNNING", null, 1, 0, 0, 2, 0,
                Instant.EPOCH, Instant.EPOCH, false));
        when(redis.topology(1, 0)).thenReturn(new DistributionScanPort.Topology("a".repeat(64),
                List.of(shard, new DistributionScanPort.Shard("b", "localhost:6380"))));
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), eq(800))).thenAnswer(invocation -> {
            starts.add(System.nanoTime());
            return page("0");
        });
        service.poll();
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("COMPLETED"), isNull());
        assertEquals(2, starts.size());
        assertTrue(starts.get(1) - starts.get(0) >= 75_000_000L);
    }
    @Test
    void commitsCursorAndAggregateTogetherAndCompletesOnlyAfterZero() {
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(1, state.getValue().observed());
        assertTrue(state.getValue().cursors().get(0).completed());
        assertEquals(1, state.getValue().entries().stream().mapToLong(DistributionCounter.Entry::count).sum());
    }
    @Test
    void checkpointFailureStopsFurtherScanning() {
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt())).thenReturn(page("12"));
        when(repository.save(any(), any(), anyString(), nullable(String.class))).thenReturn(false);
        service.poll();
        verify(repository, timeout(3000)).release(lease);
        verify(redis, times(1)).scan(anyLong(), anyInt(), any(), anyString(), anyInt());
        verify(repository, never()).save(any(), any(), eq("COMPLETED"), any());
    }
    @Test
    void oversizedResponseDiscardsPageAndPreservesCommittedState() {
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("SCAN_RESPONSE_LIMIT"));
        service.poll();
        verify(repository, timeout(3000)).save(lease, null, "INCOMPLETE", "SCAN_RESPONSE_LIMIT");
    }
    @Test
    void persistenceOutageStopsWithoutAccumulatingFurtherPages() {
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt())).thenReturn(page("12"));
        when(repository.save(any(), any(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        service.poll();
        verify(repository, timeout(3000)).release(lease);
        verify(redis, times(1)).scan(anyLong(), anyInt(), any(), anyString(), anyInt());
    }
    @Test
    void recoveryUsesCommittedCursorAndDoesNotRecountOldEntries() {
        var old = new DistributionCheckpoint(topology.fingerprint(),
                List.of(new DistributionCheckpoint.Cursor(shard, "18", false)), 0, 5, 100,
                List.of(new DistributionCounter.Entry(new DistributionClassifier.Group("r", "order", false), 5, 0)),
                1000, 0);
        when(repository.checkpoint(1)).thenReturn(Optional.of(old));
        when(redis.scan(1, 0, shard, "18", 200)).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(6, state.getValue().observed());
        verify(redis, times(1)).scan(1, 0, shard, "18", 200);
    }
    @Test
    void topologyChangeNeverReportsComplete() {
        when(redis.topology(1, 0)).thenReturn(topology,
                new DistributionScanPort.Topology("b".repeat(64), List.of(shard)));
        when(redis.scan(anyLong(), anyInt(), any(), anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("INCOMPLETE"), eq("TOPOLOGY_CHANGED"));
        verify(repository, never()).save(any(), any(), eq("COMPLETED"), any());
    }
}
