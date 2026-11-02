package io.github.susongyan.redisops.platform.application.risk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.risk.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RiskReplicaScanTest {
    final RiskScanRepository repo = mock(RiskScanRepository.class);
    final ReadOnlyScanPort reads = mock(ReadOnlyScanPort.class);
    final ReadOnlyScanPort.Session session = mock(ReadOnlyScanPort.Session.class);
    final RiskScanService service = new RiskScanService(repo, mock(ClusterRepository.class), mock(LocalJobRunner.class),
            reads, mock(AlertService.class));
    final ReadOnlyScanPort.Shard replica = new ReadOnlyScanPort.Shard("s1", "replica:6379", "replica", "run", "node");
    final ReadOnlyScanPort.Topology topology = new ReadOnlyScanPort.Topology("fingerprint", List.of(replica));
    @BeforeEach
    void setup() {
        when(repo.findTask(1)).thenReturn(Optional.of(task(1L, RiskScanStatus.RUNNING)));
        when(reads.topology(10, 0)).thenReturn(topology);
        when(repo.groupTexts(anyLong())).thenReturn(List.of());
        when(repo.saveRun(any())).thenAnswer(call -> {
            RiskScanRun run = call.getArgument(0);
            return new RiskScanRun(run.id() == null ? 9L : run.id(), run.taskId(), run.runNo(), run.status(),
                    run.plannedKeys(), run.scannedKeys(), run.findingCount(), run.noTtlTotal(), run.largeKeyTotal(),
                    run.startedAt(), run.completedAt(), run.errorCode());
        });
    }
    private static RiskScanTask task(long id, RiskScanStatus status) {
        return new RiskScanTask(id, "scan", 10, 0, "*", true, true, 512, 100000, 100, ":", 2, status, 1,
                Instant.EPOCH, Instant.EPOCH);
    }
    private void scanReturns(byte[]... keys) {
        when(reads.open(10, 0, replica)).thenReturn(session);
        when(session.scan("0", 2000)).thenReturn(new ReadOnlyScanPort.Page("0", List.of(keys)));
        List<ReadOnlyScanPort.Metadata> metadata = new ArrayList<>();
        for (int i = 0; i < keys.length; i++)
            metadata.add(new ReadOnlyScanPort.Metadata("string", 1024, -1));
        when(session.metadataBatch(anyList())).thenReturn(metadata);
    }
    @Test
    void startRejectsWhenAnotherScanRunsOnTheSameCluster() {
        RiskScanTask ready = task(2L, RiskScanStatus.CREATED);
        when(repo.findTask(2)).thenReturn(Optional.of(ready));
        when(repo.findActiveTaskForCluster(10L)).thenReturn(Optional.of(task(1L, RiskScanStatus.RUNNING)));
        BusinessException error = assertThrows(BusinessException.class, () -> service.start(2, 0, "key"));
        assertEquals("SCAN_CLUSTER_BUSY", error.code());
        verify(repo, never()).updateTask(any(), anyLong());
    }
    @Test
    void largeKeyStaysPerKeyWhileNoTtlAggregatesIntoGroups() {
        byte[] key = {1};
        scanReturns(key);
        service.execute(1);
        verify(session).metadataBatch(List.of(key));
        verify(repo).saveFindings(argThat(values -> values.size() == 1 && "LARGE_KEY".equals(values.get(0).riskType())
                && values.get(0).nodeId().equals(topology.checkpointId(replica))));
        verify(repo, atLeastOnce()).saveGroups(argThat(groups -> groups.size() == 1
                && "NO_TTL".equals(groups.get(0).riskType()) && groups.get(0).groupText().equals("\u0001")
                && groups.get(0).hitCount() == 1 && groups.get(0).sampleKey().equals("\u0001")
                && groups.get(0).maxMemoryBytes() == 1024L));
        ArgumentCaptor<RiskScanRun> runs = ArgumentCaptor.forClass(RiskScanRun.class);
        verify(repo, atLeastOnce()).saveRun(runs.capture());
        RiskScanRun completed = runs.getAllValues().stream()
                .filter(r -> r.status() == RiskScanStatus.COMPLETED).reduce((a, b) -> b).orElseThrow();
        assertEquals(1L, completed.noTtlTotal());
        assertEquals(1L, completed.largeKeyTotal());
        assertEquals(1L, completed.findingCount());
        verify(repo).updateTask(argThat(t -> t.status() == RiskScanStatus.COMPLETED), eq(1L));
    }
    @Test
    void scanCoversWholeKeyspaceEvenWhenMaxFindingsIsTiny() {
        when(repo.findTask(1)).thenReturn(Optional.of(
                new RiskScanTask(1L, "scan", 10, 0, "*", true, true, 512, 100000, 1, ":", 2,
                        RiskScanStatus.RUNNING, 1, Instant.EPOCH, Instant.EPOCH)));
        scanReturns("user:10086:a".getBytes(StandardCharsets.UTF_8), "user:10087:b".getBytes(StandardCharsets.UTF_8));
        service.execute(1);
        // both keys are large but maxFindings=1: totals stay exact, only one detail row persists, scan completes
        verify(repo).saveFindings(argThat(values -> values.size() == 1));
        ArgumentCaptor<RiskScanRun> runs = ArgumentCaptor.forClass(RiskScanRun.class);
        verify(repo, atLeastOnce()).saveRun(runs.capture());
        RiskScanRun completed = runs.getAllValues().stream()
                .filter(r -> r.status() == RiskScanStatus.COMPLETED).reduce((a, b) -> b).orElseThrow();
        assertEquals(2L, completed.largeKeyTotal());
        assertEquals(2L, completed.noTtlTotal());
        assertEquals(1L, completed.findingCount());
        verify(repo).updateTask(argThat(t -> t.status() == RiskScanStatus.COMPLETED), eq(1L));
    }
    @Test
    void numericAndHexSegmentsCollapseSoPerEntityPrefixesShareOneGroup() {
        assertEquals("user:*", RiskScanService.groupOf("user:10086:profile".getBytes(StandardCharsets.UTF_8), ":", 2));
        assertEquals("sess:*", RiskScanService.groupOf("sess:0123456789abcdef0".getBytes(StandardCharsets.UTF_8),
                ":", 2));
        assertEquals("order:*", RiskScanService.groupOf("order:2024:detail:1".getBytes(StandardCharsets.UTF_8),
                ":", 2));
    }
    @Test
    void shortKeysFallBackToWholeKeyAndUnclassifiableKeysGetSystemGroup() {
        assertEquals("abc", RiskScanService.groupOf("abc".getBytes(StandardCharsets.UTF_8), ":", 2));
        assertEquals(RiskScanService.UNCLASSIFIED,
                RiskScanService.groupOf("0123456789abcdef".getBytes(StandardCharsets.UTF_8), ":", 2));
        assertEquals(RiskScanService.BINARY_KEY, RiskScanService.groupOf(new byte[]{(byte) 0xFF}, ":", 2));
    }
    @Test
    void newPatternsFoldIntoGroupLimitOnceCapacityIsReached() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < 1000; i++)
            full.add("g" + i);
        when(repo.groupTexts(anyLong())).thenReturn(full);
        scanReturns("user:10086:profile".getBytes(StandardCharsets.UTF_8));
        service.execute(1);
        verify(repo, atLeastOnce()).saveGroups(argThat(groups -> groups.size() == 1
                && RiskScanService.GROUP_LIMIT.equals(groups.get(0).groupText())));
    }
    @Test
    void changedTopologyStopsBeforeReusingCursorAndMarksFailure() {
        when(reads.topology(10, 0)).thenReturn(topology, new ReadOnlyScanPort.Topology("changed", List.of(replica)));
        var error = assertThrows(IllegalStateException.class, () -> service.execute(1));
        assertEquals("SCAN_TOPOLOGY_UNSTABLE", error.getMessage());
        verify(reads, never()).scan(anyLong(), anyInt(), any(), anyString(), anyInt());
        verify(repo).updateTask(argThat(t -> t.status() == RiskScanStatus.FAILED), eq(1L));
    }
}
