package io.github.redisops.domain.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncTaskStatusTest {
    @Test
    void acceptsNormalTransition() {
        assertTrue(SyncTaskStatus.CREATED.canTransitionTo(SyncTaskStatus.CHECKING));
    }
    @Test
    void rejectsTerminalTransition() {
        assertFalse(SyncTaskStatus.FINISHED.canTransitionTo(SyncTaskStatus.CREATED));
    }
}
