package io.github.susongyan.redisops.platform.application.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.distribution.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class DistributionServiceTest {
    DistributionRepository repository;
    DistributionScanPort redis;
    DistributionService service;
    DistributionRepository.Lease lease = new DistributionRepository.Lease(1, 1, "test", 1);
    DistributionScanPort.Shard shard = new DistributionScanPort.Shard("a", "localhost:6379");
    DistributionScanPort.Shard shardB = new DistributionScanPort.Shard("b", "localhost:6380");
    DistributionScanPort.Session sessionA = mock(DistributionScanPort.Session.class);
    DistributionScanPort.Session sessionB = mock(DistributionScanPort.Session.class);
    RedisConnectionProfileProvider profiles;
    DistributionScanPort.Topology topology = new DistributionScanPort.Topology("a".repeat(64), List.of(shard));
    DistributionSpec spec = new DistributionSpec(1, 0,
            List.of(new DistributionRule("r", "business", "", DistributionRule.Kind.SEGMENTS, ":", 1)),
            DistributionCounter.Mode.TOP_K, 10, 1000, 60, 200, 200);
    @BeforeEach
    void setup() {
        repository = mock(DistributionRepository.class);
        redis = mock(DistributionScanPort.class);
        when(redis.topology(anyLong(), anyInt(), anyLong())).thenCallRealMethod();
        when(redis.open(anyLong(), anyInt(), eq(shard))).thenReturn(sessionA);
        when(redis.open(anyLong(), anyInt(), eq(shardB))).thenReturn(sessionB);
        profiles = id -> new RedisConnectionProfile(id, ClusterMode.STANDALONE,
                List.of("localhost:6379"), null, null, "NONE", null);
        service = new DistributionService(repository, redis, profiles, 1, 21600, 1000, 1000, 1);
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
        verify(redis, never()).open(anyLong(), anyInt(), any());
    }
    @Test
    void countAndIntervalApplyAcrossShards() {
        var configured = new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 60, 800, 80);
        when(repository.get(1)).thenReturn(new DistributionTask(1, 1, configured, "RUNNING", null, 1, 0, 0, 2, 0,
                Instant.EPOCH, Instant.EPOCH, false));
        when(redis.topology(1, 0)).thenReturn(new DistributionScanPort.Topology("a".repeat(64),
                List.of(shard, shardB)));
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        when(sessionA.scan(anyString(), eq(800))).thenAnswer(invocation -> {
            starts.add(System.nanoTime());
            return page("0");
        });
        when(sessionB.scan(anyString(), eq(800))).thenAnswer(invocation -> {
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
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(1, state.getValue().observed());
        assertTrue(state.getValue().cursors().get(0).completed());
        assertEquals(1, state.getValue().entries().stream().mapToLong(DistributionCounter.Entry::count).sum());
    }
    @Test
    void checkpointFailureStopsFurtherScanning() {
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("12"));
        when(repository.save(any(), any(), anyString(), nullable(String.class))).thenReturn(false);
        service.poll();
        verify(repository, timeout(3000)).release(lease);
        verify(sessionA, times(1)).scan(anyString(), anyInt());
        verify(repository, never()).save(any(), any(), eq("COMPLETED"), any());
    }
    @Test
    void oversizedResponseDiscardsPageAndPreservesCommittedState() {
        when(sessionA.scan(anyString(), anyInt())).thenThrow(new IllegalStateException("SCAN_RESPONSE_LIMIT"));
        service.poll();
        verify(repository, timeout(3000)).save(lease, null, "INCOMPLETE", "SCAN_RESPONSE_LIMIT");
    }
    @Test
    void persistenceOutageStopsWithoutAccumulatingFurtherPages() {
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("12"));
        when(repository.save(any(), any(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        service.poll();
        verify(repository, timeout(3000)).release(lease);
        verify(sessionA, times(1)).scan(anyString(), anyInt());
    }
    @Test
    void recoveryUsesCommittedCursorAndDoesNotRecountOldEntries() {
        var old = new DistributionCheckpoint(topology.fingerprint(),
                List.of(new DistributionCheckpoint.Cursor(shard, "18", false)), 0, 5, 100,
                List.of(new DistributionCounter.Entry(new DistributionClassifier.Group("r", "order", false), 5, 0)));
        when(repository.checkpoint(1)).thenReturn(Optional.of(old));
        when(sessionA.scan("18", 200)).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(6, state.getValue().observed());
        verify(sessionA, times(1)).scan("18", 200);
    }
    @Test
    void topologyChangeNeverReportsComplete() {
        when(redis.topology(1, 0)).thenReturn(topology,
                new DistributionScanPort.Topology("b".repeat(64), List.of(shard)));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("INCOMPLETE"), eq("TOPOLOGY_CHANGED"));
        verify(repository, never()).save(any(), any(), eq("COMPLETED"), any());
    }
    @Test
    void connectFailureBeforeProgressRequeuesInsteadOfFailing() {
        when(redis.topology(1, 0)).thenThrow(new IllegalStateException("SCAN_CONNECTION_FAILED"));
        service.poll();
        verify(repository, timeout(3000)).requeue(lease, "SCAN_CONNECTION_FAILED");
        verify(repository, never()).save(any(), any(), anyString(), any());
        verify(repository, never()).release(any());
    }
    @Test
    void laterCheckpointsPersistOffTheScanThread() {
        var brief = new DistributionSpec(1, 0, spec.rules(), spec.mode(), 10, 1000, 3, 200, 200);
        when(repository.get(1)).thenReturn(new DistributionTask(1, 1, brief, "RUNNING", null, 1, 0, 0, 1, 0,
                Instant.EPOCH, Instant.EPOCH, false));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("12"));
        List<String> writers = Collections.synchronizedList(new ArrayList<>());
        when(repository.save(any(), any(), eq("RUNNING"), isNull())).thenAnswer(invocation -> {
            writers.add(Thread.currentThread().getName());
            return true;
        });
        service.poll();
        verify(repository, timeout(6000)).release(lease);
        assertTrue(writers.size() >= 2);
        assertEquals("distribution-scan", writers.get(0));
        assertTrue(writers.contains("distribution-checkpoint"));
    }
    @Test
    void requeuePersistenceFailureStillFailsTheTask() {
        when(redis.topology(1, 0)).thenThrow(new IllegalStateException("SCAN_CONNECTION_FAILED"));
        doThrow(new IllegalStateException("database unavailable")).when(repository).requeue(any(), anyString());
        service.poll();
        verify(repository, timeout(3000)).save(lease, null, "FAILED", "SCAN_CONNECTION_FAILED");
        verify(repository, timeout(3000)).release(lease);
    }
    @Test
    void shardsScanConcurrentlyWhenParallelismAllows() throws Exception {
        when(redis.topology(1, 0)).thenReturn(new DistributionScanPort.Topology("a".repeat(64),
                List.of(shard, shardB)));
        CountDownLatch bStarted = new CountDownLatch(1);
        when(sessionA.scan(anyString(), anyInt())).thenAnswer(invocation -> {
            // shard A cannot finish before shard B has started: sequential scanning would stall
            // here until the await times out and blow past the save deadline below
            if (!bStarted.await(4, TimeUnit.SECONDS))
                throw new IllegalStateException("SCAN_NEVER_OVERLAPPED");
            return page("0");
        });
        when(sessionB.scan(anyString(), anyInt())).thenAnswer(invocation -> {
            bStarted.countDown();
            return page("0");
        });
        service.destroy();
        service = new DistributionService(repository, redis, profiles, 1, 21600, 1000, 1000, 2);
        service.poll();
        verify(repository, timeout(2500)).save(eq(lease), any(), eq("COMPLETED"), isNull());
    }
    DistributionTask runningTask() {
        return new DistributionTask(1, 1, spec, "RUNNING", null, 1, 0, 0, 1, 0, Instant.EPOCH, Instant.EPOCH,
                false);
    }
    @Test
    void createStartsLocallyWithoutTheSweep() {
        when(repository.createAndClaim(eq(spec), anyString()))
                .thenReturn(new DistributionRepository.ClaimedTask(runningTask(), lease));
        // regression: a task row born milliseconds ago can be invisible to another pooled
        // connection (weak read); the create path must not depend on re-reading it
        when(repository.get(1)).thenThrow(new BusinessException("NOT_FOUND", "keyDistribution not found: 1"));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.create(spec);
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("COMPLETED"), isNull());
        verify(repository, never()).claim(anyString());
    }
    @Test
    void createReleasesTheSlotWhenTheClusterIsBusy() {
        when(repository.createAndClaim(eq(spec), anyString()))
                .thenThrow(new BusinessException("DISTRIBUTION_CLUSTER_BUSY", "another scan holds this cluster"));
        assertThrows(BusinessException.class, () -> service.create(spec));
        when(repository.createAndClaim(eq(spec), anyString()))
                .thenReturn(new DistributionRepository.ClaimedTask(runningTask(), lease));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.create(spec);
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("COMPLETED"), isNull());
    }
    @Test
    void createRejectsWhenTheInstanceIsBusy() throws Exception {
        CountDownLatch scanEntered = new CountDownLatch(1), finishScan = new CountDownLatch(1);
        when(repository.createAndClaim(eq(spec), anyString()))
                .thenReturn(new DistributionRepository.ClaimedTask(runningTask(), lease));
        when(sessionA.scan(anyString(), anyInt())).thenAnswer(invocation -> {
            scanEntered.countDown();
            finishScan.await(5, TimeUnit.SECONDS);
            return page("0");
        });
        service.create(spec);
        assertTrue(scanEntered.await(3, TimeUnit.SECONDS));
        assertThrows(BusinessException.class, () -> service.create(spec));
        finishScan.countDown();
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("COMPLETED"), isNull());
    }
    @Test
    void resumeRestartsTheTaskLocally() {
        when(repository.control(1, 1, "resume")).thenReturn(runningTask());
        when(repository.claimTask(eq(1L), anyString())).thenReturn(Optional.of(lease));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.control(1, 1, "resume");
        verify(repository, timeout(3000)).save(eq(lease), any(), eq("COMPLETED"), isNull());
        verify(repository, never()).claim(anyString());
    }
    @Test
    void dbsizeDenominatorIsSampledOnceAndCarriedToTheTerminalState() {
        when(redis.count(1, 0, topology)).thenReturn(1234L);
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(1234L, state.getValue().estimatedTotal());
        verify(redis, times(1)).count(1, 0, topology);
    }
    @Test
    void resumeReusesTheCommittedDenominatorWithoutResampling() {
        var paused = new DistributionCheckpoint(topology.fingerprint(),
                List.of(new DistributionCheckpoint.Cursor(shard, "18", false)), 0, 5, 100, 777,
                List.of(new DistributionCounter.Entry(new DistributionClassifier.Group("r", "order", false), 5, 0)));
        when(repository.checkpoint(1)).thenReturn(Optional.of(paused));
        when(sessionA.scan("18", 200)).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(777L, state.getValue().estimatedTotal());
        verify(redis, never()).count(anyLong(), anyInt(), any());
    }
    @Test
    void denominatorSampleFailureDegradesToIndeterminateButStillScans() {
        when(redis.count(1, 0, topology)).thenThrow(new IllegalStateException("SCAN_CONNECTION_FAILED"));
        when(sessionA.scan(anyString(), anyInt())).thenReturn(page("0"));
        service.poll();
        ArgumentCaptor<DistributionCheckpoint> state = ArgumentCaptor.forClass(DistributionCheckpoint.class);
        verify(repository, timeout(3000)).save(eq(lease), state.capture(), eq("COMPLETED"), isNull());
        assertEquals(0L, state.getValue().estimatedTotal());
        assertEquals(1, state.getValue().observed());
    }
}
