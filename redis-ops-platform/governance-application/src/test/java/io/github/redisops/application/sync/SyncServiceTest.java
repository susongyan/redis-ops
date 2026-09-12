package io.github.redisops.application.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.application.relation.ClusterRelationService;
import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.job.JobRepository;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncServiceTest {

    @Test
    void startsControlledSwitchoverOnlyAfterStableRpo() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        ClusterRelation relation = fixture.relation(now);
        SyncTask task = fixture.caughtUpTask(now);
        when(fixture.relationService.get(3)).thenReturn(relation);
        when(fixture.sync.findLatestTask(3)).thenReturn(Optional.of(task));
        when(fixture.sync.findMetrics(8, 100)).thenReturn(List.of(
                metric(now.minusMillis(100), 2),
                metric(now.minusMillis(200), 3),
                metric(now.minusMillis(300), 4)));
        when(fixture.relations.update(any(), eq(4L))).thenReturn(true);
        when(fixture.sync.saveSwitchover(any())).thenAnswer(invocation -> {
            Switchover value = invocation.getArgument(0);
            return new Switchover(9L, value.relationId(), value.oldPrimaryClusterId(),
                    value.oldStandbyClusterId(), value.stoppedTaskId(), value.reverseTaskId(), value.status(),
                    value.operator(), value.sourceWriteFenced(), value.sourceFenceNote(), value.lastError(),
                    value.version(), value.createdAt(), value.updatedAt(), value.confirmedAt());
        });

        Switchover result = fixture.service.startSwitchover(3, "operator");

        assertEquals(SwitchoverStatus.WAITING_SOURCE_FENCE, result.status());
        assertFalse(result.sourceWriteFenced());
        ArgumentCaptor<ClusterRelation> switching = ArgumentCaptor.forClass(ClusterRelation.class);
        verify(fixture.relations).update(switching.capture(), eq(4L));
        assertEquals(RelationStatus.SWITCHING, switching.getValue().status());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsSwitchoverWhenAnyRpoSampleIsStale() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        when(fixture.relationService.get(3)).thenReturn(fixture.relation(now));
        when(fixture.sync.findLatestTask(3)).thenReturn(Optional.of(fixture.caughtUpTask(now)));
        when(fixture.sync.findMetrics(8, 100)).thenReturn(List.of(
                metric(now.minusSeconds(6), 2),
                metric(now.minusMillis(200), 3),
                metric(now.minusMillis(300), 4)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.startSwitchover(3, "operator"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.relations, never()).update(any(), anyLong());
    }

    @Test
    void rejectsSwitchoverWhenSourceClockIsMoreThanTwoSecondsAhead() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        when(fixture.relationService.get(3)).thenReturn(fixture.relation(now));
        when(fixture.sync.findLatestTask(3)).thenReturn(Optional.of(fixture.caughtUpTask(now)));
        when(fixture.sync.findMetrics(8, 100)).thenReturn(List.of(
                metric(now.minusMillis(100), -3),
                metric(now.minusMillis(1100), 0),
                metric(now.minusMillis(2100), 0)));

        assertThrows(BusinessException.class,
                () -> fixture.service.startSwitchover(3, "operator"));
    }

    @Test
    void rejectsSwitchoverWhenThreeSamplesAreNotContinuous() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        when(fixture.relationService.get(3)).thenReturn(fixture.relation(now));
        when(fixture.sync.findLatestTask(3)).thenReturn(Optional.of(fixture.caughtUpTask(now)));
        when(fixture.sync.findMetrics(8, 100)).thenReturn(List.of(
                metric(now.minusMillis(100), 0),
                metric(now.minusMillis(1100), 0),
                metric(now.minusMillis(4100), 0)));

        assertThrows(BusinessException.class,
                () -> fixture.service.startSwitchover(3, "operator"));
    }

    @Test
    void updatesFullApplyTuningBeforeTaskStartsWithoutEnqueueingWorkerCommand() {
        Fixture fixture = new Fixture();
        SyncTask task = fixture.task(SyncTaskStatus.CREATED);
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(task));
        when(fixture.sync.updateTask(any(), eq(2L), eq("operator"), anyString())).thenReturn(true);

        fixture.service.updateLimits(8, 2, 60_000, 200 * 1024 * 1024L,
                60L * 1024 * 1024 * 1024, 8, 250, "operator", "request-1");

        ArgumentCaptor<SyncTask> changed = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(changed.capture(), eq(2L), eq("operator"), anyString());
        assertEquals(8, changed.getValue().fullApplyConcurrency());
        assertEquals(250, changed.getValue().fullApplyPipelineSize());
        verify(fixture.jobs, never()).enqueue(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsFullApplyTuningAfterTaskStarts() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.FULL_SYNCING)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.updateLimits(8, 2, 50_000, 100 * 1024 * 1024L,
                        50L * 1024 * 1024 * 1024, 8, 100, "operator", "request-1"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsDbSelectionForCluster() {
        Fixture fixture = new Fixture();
        when(fixture.clusters.findById(11)).thenReturn(Optional.of(cluster(11, ClusterMode.CLUSTER)));
        when(fixture.clusters.findById(22)).thenReturn(Optional.of(cluster(22, ClusterMode.SENTINEL)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.create(null, 11L, 22L, SyncPurpose.ADHOC,
                        SyncMode.FULL_AND_INCREMENTAL, 1, 0, null, null,
                        null, null, null, null, null, "operator"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).saveTask(any(), anyString(), anyString());
    }

    @Test
    void requiresDbSelectionForStandalone() {
        Fixture fixture = new Fixture();
        when(fixture.clusters.findById(11)).thenReturn(Optional.of(cluster(11, ClusterMode.STANDALONE)));
        when(fixture.clusters.findById(22)).thenReturn(Optional.of(cluster(22, ClusterMode.CLUSTER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.create(null, 11L, 22L, SyncPurpose.ADHOC,
                        SyncMode.FULL_AND_INCREMENTAL, null, 0, null, null,
                        null, null, null, null, null, "operator"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).saveTask(any(), anyString(), anyString());
    }

    @Test
    void requiresDbSelectionForSentinel() {
        Fixture fixture = new Fixture();
        when(fixture.clusters.findById(11)).thenReturn(Optional.of(cluster(11, ClusterMode.SENTINEL)));
        when(fixture.clusters.findById(22)).thenReturn(Optional.of(cluster(22, ClusterMode.CLUSTER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.create(null, 11L, 22L, SyncPurpose.ADHOC,
                        SyncMode.FULL_AND_INCREMENTAL, null, 0, null, null,
                        null, null, null, null, null, "operator"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).saveTask(any(), anyString(), anyString());
    }

    private static SyncMetricSnapshot metric(Instant collectedAt, long lag) {
        return new SyncMetricSnapshot(null, 8, "source-master-1", lag, lag, 0, 0, 100, 100, 0L,
                "TIMESTAMP_HEARTBEAT", "HIGH", collectedAt);
    }

    private static RedisCluster cluster(long id, ClusterMode mode) {
        Instant now = Instant.now();
        return new RedisCluster(id, "cluster-" + id, "prod", null, "owner", null, null,
                mode, "7.4", "127.0.0.1:6379", 1L, ClusterStatus.ACTIVE, 0, now, now);
    }

    private static final class Fixture {
        private final SyncRepository sync = mock(SyncRepository.class);
        private final ClusterRelationRepository relations = mock(ClusterRelationRepository.class);
        private final ClusterRelationService relationService = mock(ClusterRelationService.class);
        private final ClusterRepository clusters = mock(ClusterRepository.class);
        private final JobRepository jobs = mock(JobRepository.class);
        private final AuditRepository audits = mock(AuditRepository.class);
        private final SyncService service = new SyncService(sync, relations, relationService, clusters, jobs, audits,
                new ObjectMapper());

        private ClusterRelation relation(Instant now) {
            return new ClusterRelation(3L, "dr", RelationType.DISASTER_RECOVERY, 11, 22,
                    RelationStatus.ACTIVE, 30, null, 4, now, now);
        }

        private SyncTask caughtUpTask(Instant now) {
            return task(SyncTaskStatus.CAUGHT_UP);
        }

        private SyncTask task(SyncTaskStatus status) {
            Instant now = Instant.now();
            return new SyncTask(8L, "SYNC-X", 3L, 11, 22, SyncPurpose.DISASTER_RECOVERY,
                    SyncMode.FULL_AND_INCREMENTAL, status, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]",
                    50_000, 100 * 1024 * 1024L, 50L * 1024 * 1024 * 1024, 4, 100, null, true,
                    "initial fence", null,
                    "epoch", 2L, null, 2, now, now, null);
        }
    }
}
