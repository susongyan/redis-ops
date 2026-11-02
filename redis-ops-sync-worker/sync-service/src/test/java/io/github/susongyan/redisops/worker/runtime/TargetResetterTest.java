package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerAssetReadPort;
import io.github.susongyan.redisops.worker.persistence.WorkerTopologyPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TargetResetterTest {

    @Test
    void flushTimeoutScalesWithKeyCountAndStaysBounded() {
        TargetResetter resetter = new TargetResetter(mock(WorkerAssetReadPort.class),
                mock(WorkerRedisConnectionProfilePort.class), mock(WorkerTopologyPort.class), 30, 60, 900);
        assertEquals(Duration.ofSeconds(30), resetter.flushTimeoutFor(0));
        assertEquals(Duration.ofSeconds(36), resetter.flushTimeoutFor(100_000));
        assertEquals(Duration.ofSeconds(90), resetter.flushTimeoutFor(1_000_000));
        assertEquals(Duration.ofSeconds(750), resetter.flushTimeoutFor(12_000_000));
        assertEquals(Duration.ofSeconds(900), resetter.flushTimeoutFor(1_000_000_000));
    }

    @Test
    void flushTimeoutNeverGoesBelowTheConfiguredBase() {
        TargetResetter resetter = new TargetResetter(mock(WorkerAssetReadPort.class),
                mock(WorkerRedisConnectionProfilePort.class), mock(WorkerTopologyPort.class), 30, 0, 900);
        assertEquals(Duration.ofSeconds(30), resetter.flushTimeoutFor(500_000_000));
    }
}
