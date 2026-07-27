package io.github.redisops.sync.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneSyncTaskRunnerTest {
    @Test
    void reportsZeroEtaWhenOffsetsAreCaughtUpWithoutThroughputSamples() {
        assertEquals(0L, StandaloneSyncTaskRunner.calculateCatchUpEta(0, 0, 0));
    }

    @Test
    void leavesEtaUnknownWhenTargetIsNotCatchingUp() {
        assertNull(StandaloneSyncTaskRunner.calculateCatchUpEta(100, 10, 10));
        assertNull(StandaloneSyncTaskRunner.calculateCatchUpEta(100, 20, 10));
    }

    @Test
    void calculatesEtaFromNetCatchUpThroughput() {
        assertEquals(5L, StandaloneSyncTaskRunner.calculateCatchUpEta(100, 10, 30));
    }

    @Test
    void keepsCaughtUpStateForImmediatelyAppliedHeartbeatOrSmallBatch() {
        assertFalse(StandaloneSyncTaskRunner.shouldLeaveCaughtUp(true, 200, 200, true));
    }

    @Test
    void leavesCaughtUpStateOnlyWhenIncrementalBacklogExists() {
        assertTrue(StandaloneSyncTaskRunner.shouldLeaveCaughtUp(true, 100, 200, true));
        assertTrue(StandaloneSyncTaskRunner.shouldLeaveCaughtUp(true, 200, 200, false));
        assertFalse(StandaloneSyncTaskRunner.shouldLeaveCaughtUp(false, 100, 200, false));
    }
}
