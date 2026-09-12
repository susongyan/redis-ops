package io.github.redisops.sync.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TargetFenceTest {
    @Test
    void roundTripsOwnerIdentityWithoutDelimiterAmbiguity() {
        TargetFence fence = new TargetFence("epoch|一", 9, "runtime|9", "10.0.0.8:worker",
                Instant.ofEpochMilli(123456));

        assertEquals(fence, TargetFence.decode(fence.encode()));
        assertTrue(TargetFence.decode(fence.encode()).ownedBy(fence));
    }

    @Test
    void ownerIdentityIncludesRuntimeAndWorker() {
        TargetFence fence = new TargetFence("epoch", 2, "runtime-a", "worker-a", Instant.now());
        TargetFence otherRuntime = new TargetFence("epoch", 2, "runtime-b", "worker-a", Instant.now());

        assertFalse(fence.ownedBy(otherRuntime));
    }
}
