package io.github.redisops.sync.worker.persistence;

import io.github.redisops.domain.sync.SyncMode;
import io.github.redisops.domain.sync.SyncPurpose;
import io.github.redisops.domain.sync.SyncRuntime;
import io.github.redisops.domain.sync.SyncTask;
import io.github.redisops.domain.sync.SyncTaskStatus;
import io.github.redisops.sync.contract.SyncContractStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformWorkerSyncStateMapperTest {
    @Test
    void mapsTaskWithoutLeakingPlatformEnums() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        SyncTask task = new SyncTask(7L, "SYNC-7", 3L, 11L, 12L, SyncPurpose.MIGRATION,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.INCR_SYNCING, "NATIVE_JAVA", 0, 0, "[\"*\"]",
                "[]", "{}", 100, 200, 300, 4, 8, "START", true, "fenced", null, "epoch", 9L, null, 5,
                now, now, null);

        var worker = PlatformWorkerSyncStateMapper.toWorkerTask(task);

        assertEquals("MIGRATION", worker.purpose());
        assertEquals("FULL_AND_INCREMENTAL", worker.syncMode());
        assertEquals(SyncContractStatus.INCR_SYNCING, worker.status());
        assertEquals(12L, worker.targetClusterId());
        assertEquals(SyncTaskStatus.INCR_SYNCING,
                PlatformWorkerSyncStateMapper.toPlatformStatus(worker.status()));
    }

    @Test
    void preservesLeaseAndFencingFields() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        SyncRuntime runtime = new SyncRuntime(7L, "runtime-7", "worker-a", now.plusSeconds(30), 6L,
                "INCR_SYNCING", now, 123L, 6L, now, 2, "TAKEOVER", null, now.minusSeconds(5), now);

        var worker = PlatformWorkerSyncStateMapper.toWorkerRuntime(runtime);

        assertEquals(6L, worker.fencingGeneration());
        assertEquals(6L, worker.targetFenceGeneration());
        assertEquals("worker-a", worker.leaseOwner());
    }

    @Test
    void mapsEverySharedStatusToThePlatformStatus() {
        Arrays.stream(SyncContractStatus.values()).forEach(status -> assertEquals(status.name(),
                PlatformWorkerSyncStateMapper.toPlatformStatus(status).name()));
    }
}
