package io.github.redisops.sync.engine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Process-local fail-closed guard for a MySQL runtime lease.
 *
 * <p>
 * The deadline uses a monotonic clock so wall-clock adjustments cannot extend
 * a worker's write authority. Once a granted lease expires or is revoked, this
 * guard can never be renewed; the runner must be discarded and claimed again.
 */
public final class LeaseGuard {
    private final LongSupplier nanoTime;
    private final long safetyMarginNanos;
    private final AtomicLong deadlineNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean revoked = new AtomicBoolean();

    public LeaseGuard(Duration safetyMargin) {
        this(System::nanoTime, safetyMargin);
    }

    LeaseGuard(LongSupplier nanoTime, Duration safetyMargin) {
        if (safetyMargin.isNegative())
            throw new IllegalArgumentException("lease safety margin cannot be negative");
        this.nanoTime = nanoTime;
        this.safetyMarginNanos = safetyMargin.toNanos();
    }

    public void grant(Duration duration) {
        if (duration.isZero() || duration.isNegative())
            throw new IllegalArgumentException("lease duration must be positive");
        if (revoked.get())
            throw new LeaseLostException("runtime lease guard was already revoked");
        long usable = duration.toNanos() - safetyMarginNanos;
        if (usable <= 0)
            throw new IllegalArgumentException("lease duration must exceed the safety margin");
        deadlineNanos.set(saturatedAdd(nanoTime.getAsLong(), usable));
    }

    public void renew(Duration duration) {
        assertValid();
        grant(duration);
    }

    public void assertValid() {
        if (revoked.get())
            throw new LeaseLostException("runtime lease is no longer valid");
        long deadline = deadlineNanos.get();
        if (deadline == Long.MIN_VALUE)
            throw new LeaseLostException("runtime lease has not been granted");
        if (nanoTime.getAsLong() >= deadline) {
            revoked.set(true);
            throw new LeaseLostException("runtime lease safety deadline elapsed");
        }
    }

    public void revoke() {
        revoked.set(true);
    }

    public boolean valid() {
        try {
            assertValid();
            return true;
        } catch (LeaseLostException ignored) {
            return false;
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException(String message) {
            super(message);
        }
    }
}
