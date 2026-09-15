package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerRuntimeObservation;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerMyBatisSyncRepositoryTest {
    @Test
    void validatesExplicitMachineIpWithoutAcceptingNamesOrLoopback() {
        assertThat(WorkerMyBatisSyncRepository.resolveIp(" 10.0.0.12 ")).isEqualTo("10.0.0.12");
        assertThat(WorkerMyBatisSyncRepository.resolveIp("2001:db8::1")).contains(":");
        for (String invalid : java.util.List.of("example.com", "127.0.0.1", "0.0.0.0", "999.1.1.1", "::1"))
            assertThatThrownBy(() -> WorkerMyBatisSyncRepository.resolveIp(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimSqlPublishesIpInSameFencedClaim() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(WorkerSyncMapper.class);
        String sql = configuration.getMappedStatement(WorkerSyncMapper.class.getName() + ".claimRuntime")
                .getBoundSql(java.util.Map.of("taskId", 7L, "runtimeId", "r", "owner", "w", "leaseSeconds", 30L,
                        "workerIp", "10.0.0.12"))
                .getSql();
        assertThat(sql).contains("worker_ip=?", "worker_ip_runtime_id=?", "fencing_generation=fencing_generation+1",
                "lease_until<CURRENT_TIMESTAMP(3)");
    }
    @Test
    void recoveryQueryKeepsSqlTokenBoundariesAndUnambiguousTaskColumns() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(WorkerSyncMapper.class);
        String sql = configuration.getMappedStatement(WorkerSyncMapper.class.getName() + ".findExpiredRecoverableTasks")
                .getBoundSql(java.util.Map.of("limit", 10)).getSql();
        assertThat(sql).startsWith("SELECT id,").contains("finished_at FROM sync_task WHERE id IN (")
                .contains("lease_until<CURRENT_TIMESTAMP(3)").contains("LIMIT ?");
    }

    private final WorkerSyncMapper mapper = mock(WorkerSyncMapper.class);
    private final WorkerMyBatisSyncRepository repository = new WorkerMyBatisSyncRepository(mapper, "10.0.0.12");

    @Test
    void rejectsIllegalTransitionWithoutWriting() {
        when(mapper.findTask(7)).thenReturn(task(SyncContractStatus.CREATED, 3));

        assertThatThrownBy(() -> repository.engineTransition(7, 3, SyncContractStatus.FULL_SYNCING, null,
                null, null, "bad", "worker"))
                .isInstanceOf(IllegalStateException.class);

        verify(mapper, never()).transitionTask(anyLong(), anyLong(), anyString(), any(), any(), any());
        verify(mapper, never()).insertEvent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void rejectsVersionConflictBeforeWritingEvent() {
        when(mapper.findTask(7)).thenReturn(task(SyncContractStatus.STARTING, 3));
        when(mapper.transitionTask(7, 3, "FULL_SYNCING", null, null, null)).thenReturn(0);

        assertThatThrownBy(() -> repository.engineTransition(7, 3, SyncContractStatus.FULL_SYNCING, null,
                null, null, "start", "worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version conflict");

        verify(mapper, never()).insertEvent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void transitionsThenWritesEventInTransactionalBoundary() throws NoSuchMethodException {
        when(mapper.findTask(7)).thenReturn(task(SyncContractStatus.STARTING, 3));
        when(mapper.transitionTask(7, 3, "FULL_SYNCING", null, null, null)).thenReturn(1);

        repository.engineTransition(7, 3, SyncContractStatus.FULL_SYNCING, null, null, null, "start", "worker");

        InOrder order = inOrder(mapper);
        order.verify(mapper).transitionTask(7, 3, "FULL_SYNCING", null, null, null);
        order.verify(mapper).insertEvent(7, "STARTING", "FULL_SYNCING", "worker", "start");
        assertThat(WorkerMyBatisSyncRepository.class
                .getMethod("engineTransition", long.class, long.class, SyncContractStatus.class, Long.class,
                        String.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void claimsThenRenewsWithProvidedOwner() {
        when(mapper.claimRuntime(7, "runtime", "worker-a", 30, "10.0.0.12")).thenReturn(1);
        when(mapper.renewRuntime(7, "worker-a", 30, "RUNNING", 9)).thenReturn(1);

        assertThat(repository.claimRuntime(7, "runtime", "worker-a", 30)).isTrue();
        assertThat(repository.renewRuntime(7, "worker-a", 30, "RUNNING", 9)).isTrue();
        repository.releaseRuntime(7, "worker-a", "STOPPED", null);
        verify(mapper).ensureRuntime(7, "runtime");
        verify(mapper).releaseRuntime(7, "worker-a", "STOPPED", null);
    }

    @Test
    void failsClosedWhenRuntimeOwnerNoLongerMatches() {
        WorkerRuntimeObservation observation = new WorkerRuntimeObservation(7, "old-worker", "LEASE_LOST", 4L,
                Instant.now(), null, "lost");
        when(mapper.updateRuntimeObservation(anyLong(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.updateRuntimeObservation(observation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime lease lost");
    }

    private static WorkerSyncTask task(SyncContractStatus status, long version) {
        Instant now = Instant.now();
        return new WorkerSyncTask(7L, "SYNC-7", null, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL", status,
                "NATIVE_JAVA", 0, 0, "[]", "[]", "{}", 0, 0, 1, 1, 1, "START", true, null, null, null,
                null, null, version, now, now, null);
    }
}
