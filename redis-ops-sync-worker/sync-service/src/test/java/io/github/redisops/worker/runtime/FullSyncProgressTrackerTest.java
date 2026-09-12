package io.github.redisops.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.redisops.worker.domain.WorkerSyncFullProgress;
import io.github.redisops.worker.persistence.WorkerSyncExecutionPort;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FullSyncProgressTrackerTest {
    @Test
    void reportsReceiveParseLanesAndCompletion() {
        WorkerSyncExecutionPort repository = mock(WorkerSyncExecutionPort.class);
        FullSyncProgressTracker tracker = new FullSyncProgressTracker(7, "epoch-1", "source-a", 2, repository);

        tracker.startReceiving(1_000);
        tracker.received(500);
        tracker.rdbReceived(1_000);
        tracker.parsed(700, true);
        tracker.parsed(900, true);
        tracker.parsingComplete(1_000);
        tracker.applied(0, 1, 300);
        tracker.applied(1, 1, 400);
        tracker.completed();
        tracker.failed();

        ArgumentCaptor<WorkerSyncFullProgress> captor = ArgumentCaptor.forClass(WorkerSyncFullProgress.class);
        verify(repository, atLeastOnce()).upsertFullProgress(captor.capture());
        List<WorkerSyncFullProgress> rows = new ArrayList<>(captor.getAllValues());
        WorkerSyncFullProgress aggregate = rows.stream()
                .filter(row -> row.lane() == -1 && row.status().equals("COMPLETED"))
                .reduce((first, second) -> second).orElseThrow();

        assertThat(aggregate.totalBytes()).isEqualTo(1_000);
        assertThat(aggregate.receivedBytes()).isEqualTo(1_000);
        assertThat(aggregate.parsedBytes()).isEqualTo(1_000);
        assertThat(aggregate.totalKeys()).isEqualTo(2);
        assertThat(aggregate.appliedKeys()).isEqualTo(2);
        assertThat(aggregate.appliedBytes()).isEqualTo(700);
        assertThat(rows).noneMatch(row -> row.status().equals("FAILED"));
        assertThat(rows).anyMatch(row -> row.lane() == 0 && row.appliedKeys() == 1);
        assertThat(rows).anyMatch(row -> row.lane() == 1 && row.appliedKeys() == 1);
    }

    @Test
    void observationFailureDoesNotInterruptSync() {
        WorkerSyncExecutionPort repository = mock(WorkerSyncExecutionPort.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository)
                .upsertFullProgress(any());
        FullSyncProgressTracker tracker = new FullSyncProgressTracker(8, "epoch-2", "standalone", 1, repository);

        tracker.startReceiving(10);
        tracker.received(10);
        tracker.rdbReceived(10);
        tracker.parsed(10, true);
        tracker.parsingComplete(10);
        tracker.applied(0, 1, 10);
        tracker.completed();
    }
}
