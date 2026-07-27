package io.github.redisops.sync.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LeaseGuardTest {
    @Test
    void expiresAgainstMonotonicDeadlineAndCannotBeRenewedAfterLongPause() {
        AtomicLong clock = new AtomicLong();
        LeaseGuard guard = new LeaseGuard(clock::get, Duration.ofSeconds(2));
        guard.grant(Duration.ofSeconds(30));

        clock.set(Duration.ofSeconds(27).toNanos());
        assertDoesNotThrow(guard::assertValid);

        clock.set(Duration.ofSeconds(28).toNanos());
        assertThrows(LeaseGuard.LeaseLostException.class, guard::assertValid);
        assertThrows(LeaseGuard.LeaseLostException.class,
                () -> guard.renew(Duration.ofSeconds(30)));
    }

    @Test
    void revokeIsImmediateAndIrreversible() {
        LeaseGuard guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofSeconds(30));

        guard.revoke();

        assertFalse(guard.valid());
        assertThrows(LeaseGuard.LeaseLostException.class,
                () -> guard.grant(Duration.ofSeconds(30)));
    }
}
