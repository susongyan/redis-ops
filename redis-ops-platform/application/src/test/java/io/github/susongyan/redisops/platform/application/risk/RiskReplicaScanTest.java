package io.github.susongyan.redisops.platform.application.risk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import io.github.susongyan.redisops.platform.domain.risk.*;
import io.github.susongyan.redisops.platform.domain.scan.ReadOnlyScanPort;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskReplicaScanTest {
    final RiskScanRepository repo = mock(RiskScanRepository.class);
    final ReadOnlyScanPort reads = mock(ReadOnlyScanPort.class);
    final RiskScanService service = new RiskScanService(repo, mock(ClusterRepository.class), mock(JobRepository.class),
            reads, mock(AlertService.class));
    final ReadOnlyScanPort.Shard replica = new ReadOnlyScanPort.Shard("s1", "replica:6379", "replica", "run", "node");
    final ReadOnlyScanPort.Topology topology = new ReadOnlyScanPort.Topology("fingerprint", List.of(replica));
    final RiskScanTask task = new RiskScanTask(1L, "scan", 10, 0, "*", true, true, 512, 100000, 100,
            RiskScanStatus.RUNNING, 1, Instant.EPOCH, Instant.EPOCH);
    @BeforeEach
    void setup() {
        when(repo.findTask(1)).thenReturn(Optional.of(task));
        when(reads.topology(10, 0)).thenReturn(topology);
        when(repo.saveRun(any())).thenAnswer(call -> {
            RiskScanRun run = call.getArgument(0);
            return new RiskScanRun(9L, run.taskId(), run.runNo(), run.status(), run.plannedKeys(), run.scannedKeys(),
                    run.findingCount(), run.startedAt(), run.completedAt(), run.errorCode());
        });
    }
    @Test
    void keyInspectionStaysOnReplicaAndStoresSource() {
        byte[] key = {1};
        when(reads.scan(10, 0, replica, "0", 200)).thenReturn(new ReadOnlyScanPort.Page("0", List.of(key)));
        when(reads.metadata(10, 0, replica, key)).thenReturn(new ReadOnlyScanPort.Metadata("string", 1024, -1));
        service.execute(1);
        verify(reads).metadata(10, 0, replica, key);
        verify(repo).saveFindings(argThat(values -> values.size() == 2 && values.stream()
                .allMatch(f -> f.nodeId().equals(topology.checkpointId(replica)))));
        verify(repo).updateTask(argThat(t -> t.status() == RiskScanStatus.COMPLETED), eq(1L));
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
