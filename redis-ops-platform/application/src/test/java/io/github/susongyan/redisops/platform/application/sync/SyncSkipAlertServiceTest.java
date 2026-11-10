package io.github.susongyan.redisops.platform.application.sync;

import io.github.susongyan.redisops.platform.application.alert.AlertService;
import io.github.susongyan.redisops.platform.domain.sync.SyncRepository;
import io.github.susongyan.redisops.platform.domain.sync.SyncTask;
import io.github.susongyan.redisops.platform.domain.sync.SyncTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncSkipAlertServiceTest {

    @Test
    void alertsEachFreshTaskOnceAndSuppressesWhileUnresolved() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findSplitSkipTaskIds(600)).thenReturn(List.of(8L, 9L));
        when(fixture.sync.countSplitSkips(8)).thenReturn(3L);
        when(fixture.sync.countSplitSkips(9)).thenReturn(1L);
        when(fixture.sync.findTask(8)).thenReturn(Optional.of(fixture.task(8, "SYNC-8")));
        when(fixture.sync.findTask(9)).thenReturn(Optional.of(fixture.task(9, "SYNC-9")));
        when(fixture.alerts.hasUnresolved(anyString(), anyString(), anyString())).thenReturn(false, true);

        assertEquals(1, fixture.service.alertRecentSkips());
        verify(fixture.alerts).trigger(eq("LARGE_KEY_FOUND"), eq("SYNC_TASK"), eq("8"), eq(3d), contains("SYNC-8"));
        verify(fixture.alerts, never()).trigger(eq("LARGE_KEY_FOUND"), eq("SYNC_TASK"), eq("9"), anyDouble(),
                anyString());
    }

    @Test
    void nothingFreshMeansNoQueriesPerTask() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findSplitSkipTaskIds(600)).thenReturn(List.of());
        assertEquals(0, fixture.service.alertRecentSkips());
        verify(fixture.alerts, never()).trigger(anyString(), anyString(), anyString(), anyDouble(), anyString());
        verify(fixture.sync, never()).countSplitSkips(anyLong());
    }

    @Test
    void vanishedEventsOrMissingTaskDoNotBlowUp() {
        Fixture fixture = new Fixture();
        when(fixture.sync.findSplitSkipTaskIds(600)).thenReturn(List.of(8L, 9L));
        when(fixture.sync.countSplitSkips(8)).thenReturn(0L); // events vanished mid-scan
        when(fixture.sync.countSplitSkips(9)).thenReturn(2L);
        when(fixture.sync.findTask(9)).thenReturn(Optional.empty()); // task row gone

        assertEquals(1, fixture.service.alertRecentSkips());
        verify(fixture.alerts, never()).trigger(anyString(), anyString(), eq("8"), anyDouble(), anyString());
        verify(fixture.alerts).trigger(eq("LARGE_KEY_FOUND"), eq("SYNC_TASK"), eq("9"), eq(2d),
                contains("task-9"));
    }

    private static final class Fixture {
        private final SyncRepository sync = mock(SyncRepository.class);
        private final AlertService alerts = mock(AlertService.class);
        private final SyncSkipAlertService service = new SyncSkipAlertService(sync, alerts, 600);

        private SyncTask task(long id, String taskNo) {
            Instant now = Instant.now();
            return new SyncTask(id, taskNo, 3L, 11, 22,
                    io.github.susongyan.redisops.platform.domain.sync.SyncPurpose.DISASTER_RECOVERY,
                    io.github.susongyan.redisops.platform.domain.sync.SyncMode.FULL_AND_INCREMENTAL,
                    SyncTaskStatus.FULL_SYNCING, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]",
                    50_000, 100 * 1024 * 1024L, 50L * 1024 * 1024 * 1024, 4, 100, null, true,
                    "initial fence", null,
                    "epoch", 2L, null, 2, now, now, null);
        }
    }
}
