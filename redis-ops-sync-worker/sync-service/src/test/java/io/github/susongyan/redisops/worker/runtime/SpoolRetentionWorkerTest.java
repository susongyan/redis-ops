package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerSyncStatePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpoolRetentionWorkerTest {
    @TempDir
    Path dataDir;

    @Test
    void deletesFlaggedOrExpiredDirectoriesAndReports() throws IOException {
        WorkerSyncStatePort state = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        Path task7 = spool(7, 128);
        when(state.findSpoolPurgeTaskIds(anyCollection(), eq(3600L))).thenReturn(List.of(7L));

        new SpoolRetentionWorker(state, runners, dataDir, 3600).sweep();

        assertFalse(Files.exists(task7));
        verify(state).zeroRuntimeSpoolBytes(7);
        verify(state).appendEngineEvent(eq(7L), contains("bytes=128"), eq("sync:spool-retention"));
    }

    @Test
    void keepsTasksNotSelectedByThePurgeQuery() throws IOException {
        WorkerSyncStatePort state = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        Path task8 = spool(8, 64);
        when(state.findSpoolPurgeTaskIds(anyCollection(), eq(3600L))).thenReturn(List.of());

        new SpoolRetentionWorker(state, runners, dataDir, 3600).sweep();

        assertTrue(Files.exists(task8));
        verify(state, never()).zeroRuntimeSpoolBytes(anyLong());
        verify(runners).isManaged(8);
    }

    @Test
    void neverQueriesManagedTasksOrNonNumericEntries() throws IOException {
        WorkerSyncStatePort state = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);
        spool(9, 32);
        Files.createDirectories(dataDir.resolve("not-a-task"));
        when(runners.isManaged(9)).thenReturn(true);

        new SpoolRetentionWorker(state, runners, dataDir, 3600).sweep();

        // Only the managed task was on disk, so no purge query happens at all.
        verifyNoInteractions(state);
        assertTrue(Files.isDirectory(dataDir.resolve("not-a-task")));
        assertTrue(Files.isDirectory(dataDir.resolve("9")));
    }

    @Test
    void missingDataDirectorySkipsTheQueryEntirely() {
        WorkerSyncStatePort state = mock(WorkerSyncStatePort.class);
        NativeSyncRunnerManager runners = mock(NativeSyncRunnerManager.class);

        new SpoolRetentionWorker(state, runners, dataDir.resolve("absent"), 3600).sweep();

        verifyNoInteractions(state);
        verifyNoInteractions(runners);
    }

    private Path spool(long taskId, int bytes) throws IOException {
        Path channel = Files.createDirectories(dataDir.resolve(String.valueOf(taskId)).resolve("master-1-6379"));
        Files.write(channel.resolve("commands-00000000.pseg"), new byte[bytes]);
        return channel.getParent();
    }

    private static String contains(String text) {
        return org.mockito.ArgumentMatchers.contains(text);
    }
}
