package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FullRestoreQueueBudget}, including the oversized-item semantics. */
class FullRestoreQueueBudgetTest {

    private static TargetCommandSession.RestoreRequest request(String key, int payloadLength) {
        return new TargetCommandSession.RestoreRequest(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                -1, new byte[payloadLength]);
    }

    @Test
    void permitsTrackTheEstimatedWireBytesOfASmallRequest() {
        FullRestoreQueueBudget budget = new FullRestoreQueueBudget(64 * 1024);
        // key "k" (1 byte) + 100-byte payload + 128 overhead.
        assertEquals(1 + 100 + 128, budget.permits(request("k", 100)));
    }

    @Test
    void oversizedRequestReservesTheWholeBudgetInsteadOfBeingRejected() {
        FullRestoreQueueBudget budget = new FullRestoreQueueBudget(1024);
        // Larger than the entire budget: capped so it can still pass once the queue drains.
        assertEquals(1024, budget.permits(request("k", 10_000)));
    }

    @Test
    void budgetRejectsConfigBelowOneKilobyte() {
        assertThrows(IllegalArgumentException.class, () -> new FullRestoreQueueBudget(1023));
    }

    @Test
    void permitsBlockUntilReleasedAndOversizedItemPassesOnceQueueDrains() throws Exception {
        FullRestoreQueueBudget budget = new FullRestoreQueueBudget(1024);
        assertTrue(budget.tryAcquire(1024, 10, TimeUnit.MILLISECONDS));
        // The whole budget is held: nothing else may acquire, not even another oversized item.
        assertFalse(budget.tryAcquire(1, 10, TimeUnit.MILLISECONDS));
        budget.release(1024);
        assertTrue(budget.tryAcquire(budget.permits(request("k", 10_000)), 10, TimeUnit.MILLISECONDS));
    }
}
