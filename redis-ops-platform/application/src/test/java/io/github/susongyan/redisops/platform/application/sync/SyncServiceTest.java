package io.github.susongyan.redisops.platform.application.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.application.relation.ClusterRelationService;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import io.github.susongyan.redisops.platform.domain.relation.*;
import io.github.susongyan.redisops.platform.domain.sync.*;
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
    void rejectsNullBlankAndOversizeUtf8RulesBeforePersistence() {
        for (String rule : new String[]{null, " ", "a".repeat(1025), "中".repeat(342)}) {
            Fixture fixture = new Fixture();
            when(fixture.clusters.findById(11)).thenReturn(Optional.of(cluster(11, ClusterMode.STANDALONE)));
            when(fixture.clusters.findById(22)).thenReturn(Optional.of(cluster(22, ClusterMode.STANDALONE)));
            BusinessException error = assertThrows(BusinessException.class,
                    () -> fixture.service.create(null, 11L, 22L, SyncPurpose.ADHOC,
                            SyncMode.FULL_AND_INCREMENTAL, 0, 0, java.util.Collections.singletonList(rule), List.of(),
                            null, null, null, null, null, "operator"));
            assertEquals("INVALID_ARGUMENT", error.code());
            verify(fixture.sync, never()).saveTask(any(), anyString(), anyString());
        }
    }

    @Test
    void workerAssignmentsUseOneBoundedBatchAndRejectInvalidIds() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findWorkerAssignments(List.of(1L, 2L))).thenReturn(List.of());
        assertEquals(List.of(), fixture.service.workerAssignments(List.of(1L, 2L, 1L)));
        verify(fixture.sync).findWorkerAssignments(List.of(1L, 2L));
        assertThrows(BusinessException.class, () -> fixture.service.workerAssignments(List.of()));
        assertThrows(BusinessException.class, () -> fixture.service.workerAssignments(List.of(-1L)));
        assertThrows(BusinessException.class,
                () -> fixture.service.workerAssignments(java.util.Collections.nCopies(101, 1L)));
    }

    @Test
    void spoolCleanupIsFlaggedOnlyForTerminalTasks() {
        Fixture fixture = new Fixture();
        for (SyncTaskStatus status : new SyncTaskStatus[]{SyncTaskStatus.FINISHED, SyncTaskStatus.CANCELLED,
                SyncTaskStatus.FAILED}) {
            when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(status)));
            fixture.service.requestSpoolCleanup(8, "operator");
        }
        verify(fixture.sync, times(3)).requestSpoolCleanup(8, "operator");

        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.INCR_SYNCING)));
        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestSpoolCleanup(8, "operator"));
        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, times(3)).requestSpoolCleanup(8, "operator");
    }

    @Test
    void finishAcceptsMissingFenceNoteButRequiresFenceConfirmation() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.INCR_SYNCING)));
        when(fixture.sync.updateTask(any(), anyLong(), anyString(), anyString())).thenReturn(true);
        fixture.service.requestFinish(8, 2, true, null, "operator", "key");
        verify(fixture.sync).updateTask(any(), eq(2L), eq("operator"), eq("final offset drain requested"));
        verify(fixture.jobs).enqueue(eq("SYNC_FINISH"), eq(8L), anyString(), eq("sync:8:FINISH:key"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestFinish(8, 2, false, null, "operator", "key2"));
        assertEquals("INVALID_ARGUMENT", error.code());
    }

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
    void startsWithPreserveModeWithoutFlushConfirmation() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.READY)));
        when(fixture.sync.findLatestPrecheck(8)).thenReturn(Optional.of(new SyncPrecheckReport(1L, 8, "PASSED",
                "{}", Instant.now(), Instant.now().plusSeconds(600))));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);

        fixture.service.requestStart(8, 2, true, "fence-note", "PRESERVE", false, null, "operator", "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertEquals(SyncTaskStatus.STARTING, saved.getValue().status());
        assertEquals(SyncTask.RESET_MODE_PRESERVE, saved.getValue().resetMode());
        assertEquals("START", saved.getValue().desiredAction());
    }

    @Test
    void startKeepsWorkerAssignmentChosenAtPrecheck() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(
                Optional.of(fixture.task(SyncTaskStatus.READY).withAssignedWorker("worker-1")));
        when(fixture.sync.findLatestPrecheck(8)).thenReturn(Optional.of(new SyncPrecheckReport(1L, 8, "PASSED",
                "{}", Instant.now(), Instant.now().plusSeconds(600))));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);

        fixture.service.requestStart(8, 2, true, "fence-note", "PRESERVE", false, null, "operator", "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertEquals("worker-1", saved.getValue().assignedWorker());
        verify(fixture.sync, never()).findWorker(anyString(), anyLong());
    }

    @Test
    void startReassignsToExplicitOnlineWorker() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(
                Optional.of(fixture.task(SyncTaskStatus.READY).withAssignedWorker("worker-1")));
        when(fixture.sync.findLatestPrecheck(8)).thenReturn(Optional.of(new SyncPrecheckReport(1L, 8, "PASSED",
                "{}", Instant.now(), Instant.now().plusSeconds(600))));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);
        when(fixture.sync.findWorker(eq("worker-2"), anyLong())).thenReturn(Optional.of(online("worker-2")));

        fixture.service.requestStart(8, 2, true, "fence-note", "PRESERVE", false, "worker-2", "operator",
                "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertEquals("worker-2", saved.getValue().assignedWorker());
    }

    @Test
    void precheckAssignsOnlineWorker() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.CREATED)));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);
        when(fixture.sync.findWorker(eq("worker-1"), anyLong())).thenReturn(Optional.of(online("worker-1")));

        fixture.service.requestPrecheck(8, 2, "worker-1", "operator", "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertEquals("worker-1", saved.getValue().assignedWorker());
    }

    @Test
    void precheckWithoutWorkerClearsAssignment() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(
                Optional.of(fixture.task(SyncTaskStatus.READY).withAssignedWorker("worker-1")));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);

        fixture.service.requestPrecheck(8, 2, null, "operator", "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertNull(saved.getValue().assignedWorker());
    }

    @Test
    void precheckRejectsUnregisteredWorker() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.CREATED)));
        when(fixture.sync.findWorker(eq("ghost"), anyLong())).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestPrecheck(8, 2, "ghost", "operator", "request-1"));

        assertEquals("WORKER_UNAVAILABLE", error.code());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void precheckRejectsStaleWorkerHeartbeat() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.CREATED)));
        when(fixture.sync.findWorker(eq("worker-1"), anyLong())).thenReturn(Optional.of(
                new SyncWorkerInstance("worker-1", "10.0.0.1", 19090, "0.1.0", Instant.now(),
                        Instant.now().minusSeconds(600), false)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestPrecheck(8, 2, "worker-1", "operator", "request-1"));

        assertEquals("WORKER_UNAVAILABLE", error.code());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void purgesRegistryRowsBeyondRetentionWindow() {
        Fixture fixture = new Fixture();
        when(fixture.sync.purgeOfflineWorkers(21600)).thenReturn(3);

        assertEquals(3, fixture.service.purgeOfflineWorkers());

        verify(fixture.sync).purgeOfflineWorkers(21600);
    }

    @Test
    void workerLoadPullsLiveSnapshotFromTheReportedAddress() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findWorker("worker-1", 60)).thenReturn(Optional.of(online("worker-1")));
        WorkerLoad snapshot = new WorkerLoad("worker-1", 12.5, 3.2, 1L << 20, 1L << 21, 8L << 30, 4L << 30,
                "/data/sync", 512L << 20, 9L << 30, 10L << 30);
        when(fixture.loads.fetch("10.0.0.1", 19090)).thenReturn(snapshot);

        assertEquals(snapshot, fixture.service.workerLoad("worker-1"));
    }

    @Test
    void workerLoadRejectedWhenAddressUnreported() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findWorker("worker-1", 60)).thenReturn(Optional.of(
                new SyncWorkerInstance("worker-1", null, null, "0.1.0", Instant.now(), Instant.now(), true)));

        assertEquals("WORKER_ADDRESS_UNREPORTED",
                assertThrows(BusinessException.class, () -> fixture.service.workerLoad("worker-1")).code());
        verify(fixture.loads, never()).fetch(any(), anyInt());
    }

    @Test
    void workerLoadUnavailableWhenFetcherFails() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findWorker("worker-1", 60)).thenReturn(Optional.of(online("worker-1")));
        when(fixture.loads.fetch("10.0.0.1", 19090))
                .thenThrow(new IllegalStateException("worker unreachable: timeout"));

        assertEquals("WORKER_LOAD_UNAVAILABLE",
                assertThrows(BusinessException.class, () -> fixture.service.workerLoad("worker-1")).code());
    }

    @Test
    void runtimeRowStripsTheProcessUuidToTheStableInstanceName() {
        String owner = "local-sync-1b2c3d4e-0000-4000-8000-4d4f5e6a7b8c";
        assertEquals("local-sync", new SyncWorkerRuntimeRow(1, "T1", "FULL_SYNCING", owner, "FULL_SYNC", null,
                null, null, null).stableInstance());
        // A name without a trailing UUID (legacy or manual row) passes through untouched.
        assertEquals("legacy-worker", new SyncWorkerRuntimeRow(1, "T1", "FULL_SYNCING", "legacy-worker", null, null,
                null, null, null).stableInstance());
    }

    private static SyncWorkerInstance online(String name) {
        return new SyncWorkerInstance(name, "10.0.0.1", 19090, "0.1.0", Instant.now(), Instant.now(), true);
    }

    @Test
    void rejectsFlushModeWithoutFlushConfirmation() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.READY)));
        when(fixture.sync.findLatestPrecheck(8)).thenReturn(Optional.of(new SyncPrecheckReport(1L, 8, "PASSED",
                "{}", Instant.now(), Instant.now().plusSeconds(600))));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestStart(8, 2, true, "fence-note", "FLUSH", false,
                        null, "operator", "request-1"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void restartFullTransitionsBlockedTaskWithFreshEpochAndKeepsFenceChoice() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.BLOCKED)
                .withStatus(SyncTaskStatus.BLOCKED, null, "BLOCKED_REQUIRES_FULL_RESYNC", null, null)));
        when(fixture.sync.updateTask(any(), eq(2L), anyString(), anyString())).thenReturn(true);

        fixture.service.requestRestartFull(8, 2, null, false, null, null, "operator", "request-1");

        ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
        verify(fixture.sync).updateTask(saved.capture(), eq(2L), anyString(), anyString());
        assertEquals(SyncTaskStatus.RESUMING, saved.getValue().status());
        assertEquals("RESTART_FULL", saved.getValue().desiredAction());
        assertNull(saved.getValue().blockedReason());
        assertEquals(SyncTask.RESET_MODE_PRESERVE, saved.getValue().resetMode());
        assertTrue(saved.getValue().writeFenced());
        assertEquals("initial fence", saved.getValue().writeFenceNote());
        assertNotEquals("epoch", saved.getValue().fullSyncEpoch());
        verify(fixture.jobs).enqueue(eq("SYNC_RESTART_FULL"), eq(8L), argThat(json -> json != null
                && json.contains("\"resetMode\":\"PRESERVE\"") && json.contains("\"allowTargetFlush\":false")
                && json.contains(saved.getValue().fullSyncEpoch())), eq("sync:8:RESTART_FULL:request-1"));
    }

    @Test
    void restartFullRejectsTasksThatAreNotBlocked() {
        Fixture fixture = new Fixture();
        for (SyncTaskStatus status : new SyncTaskStatus[]{SyncTaskStatus.READY, SyncTaskStatus.PAUSED,
                SyncTaskStatus.FULL_SYNCING, SyncTaskStatus.CAUGHT_UP, SyncTaskStatus.CANCELLED}) {
            when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(status)));
            BusinessException error = assertThrows(BusinessException.class,
                    () -> fixture.service.requestRestartFull(8, 2, "PRESERVE", false, null, null, "operator",
                            "request-1"));
            assertEquals("INVALID_ARGUMENT", error.code());
        }
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
        verify(fixture.jobs, never()).enqueue(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void restartFullRejectsFlushWithoutTargetFlushAuthorization() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(SyncTaskStatus.BLOCKED)
                .withStatus(SyncTaskStatus.BLOCKED, null, "BLOCKED_REQUIRES_FULL_RESYNC", null, null)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.requestRestartFull(8, 2, "FLUSH", false, null, null, "operator",
                        "request-1"));

        assertEquals("INVALID_ARGUMENT", error.code());
        verify(fixture.sync, never()).updateTask(any(), anyLong(), anyString(), anyString());
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
    void savesExplicitPolicyVersionWithoutUpgradingLegacyRequests() throws Exception {
        for (String version : new String[]{null, "v1", "v2", "v3"}) {
            Fixture fixture = new Fixture();
            when(fixture.clusters.findById(11)).thenReturn(Optional.of(cluster(11, ClusterMode.STANDALONE)));
            when(fixture.clusters.findById(22)).thenReturn(Optional.of(cluster(22, ClusterMode.STANDALONE)));
            when(fixture.sync.saveTask(any(), anyString(), anyString()))
                    .thenReturn(fixture.task(SyncTaskStatus.CREATED));
            fixture.service.create(null, 11L, 22L, SyncPurpose.ADHOC,
                    SyncMode.FULL_AND_INCREMENTAL, 0, 0, List.of("biz:*"), List.of(),
                    null, null, null, null, null, false, true, java.util.Set.of(), version, "operator");
            ArgumentCaptor<SyncTask> saved = ArgumentCaptor.forClass(SyncTask.class);
            verify(fixture.sync).saveTask(saved.capture(), eq("operator"), anyString());
            assertEquals(version == null ? "v3" : version,
                    new ObjectMapper().readTree(saved.getValue().commandPolicyJson()).get("policyVersion").asText());
        }
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
        private final WorkerLoadPort loads = mock(WorkerLoadPort.class);
        private final SyncService service = new SyncService(sync, relations, relationService, clusters, jobs, audits,
                new ObjectMapper(), 60, 21600, loads);

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
