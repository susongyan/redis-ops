package io.github.redisops.worker.persistence;

import io.github.redisops.worker.runtime.WorkerControlJob;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerMyBatisControlJobRepositoryTest {
    private final WorkerControlJobMapper mapper = mock(WorkerControlJobMapper.class);
    private final WorkerMyBatisControlJobRepository repository = new WorkerMyBatisControlJobRepository(mapper);

    @Test
    void claimsRoutedJobWithRuntimeOwnerAndLease() {
        WorkerControlJob job = new WorkerControlJob(9, "SYNC_RESUME", 7, "worker-a");
        when(mapper.claimRouted("SYNC_RESUME", "worker-a", "runtime-a", 30, true)).thenReturn(1);
        when(mapper.findClaimed("worker-a")).thenReturn(job);

        assertThat(repository.claimForRuntime("SYNC_RESUME", "worker-a", "runtime-a", Duration.ofSeconds(30), true))
                .contains(job);
        verify(mapper).claimRouted("SYNC_RESUME", "worker-a", "runtime-a", 30, true);
    }

    @Test
    void failsClosedWhenCompletionOrRetryLostLease() {
        when(mapper.complete(9, "old-worker")).thenReturn(0);
        when(mapper.retryOrFail(9, "old-worker", "failure")).thenReturn(0);

        assertThatThrownBy(() -> repository.complete(9, "old-worker"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease lost");
        assertThatThrownBy(() -> repository.retryOrFail(9, "old-worker", "failure"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease lost");
    }

    @Test
    void reportsWhetherRetryReachedTerminalFailure() {
        when(mapper.retryOrFail(9, "worker-a", "failure")).thenReturn(1);
        when(mapper.status(9)).thenReturn("RETRY", "FAILED");

        assertThat(repository.retryOrFail(9, "worker-a", "failure")).isFalse();
        assertThat(repository.retryOrFail(9, "worker-a", "failure")).isTrue();
    }
}
