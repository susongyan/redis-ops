package io.github.susongyan.redisops.platform.scheduling;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/** Fixed delay from completion, with a fresh bounded jitter for each poll. */
final class DiscoveryPollTrigger implements Trigger {
    private final long intervalMillis;
    private final long jitterMillis;
    private final LongUnaryOperator random;

    DiscoveryPollTrigger(long intervalMillis, long jitterMillis) {
        this(intervalMillis, jitterMillis, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    DiscoveryPollTrigger(long intervalMillis, long jitterMillis, LongUnaryOperator random) {
        if (intervalMillis < 1 || intervalMillis > 86_400_000
                || jitterMillis < 0 || jitterMillis >= intervalMillis) {
            throw new IllegalArgumentException(
                    "Discovery poll interval must be 1..86400000 ms; jitter must be >= 0 and < interval");
        }
        this.intervalMillis = intervalMillis;
        this.jitterMillis = jitterMillis;
        this.random = random;
    }

    @Override
    public Instant nextExecution(TriggerContext context) {
        Instant base = context.lastCompletion();
        if (base == null)
            base = context.getClock().instant();
        long delay = intervalMillis;
        if (jitterMillis > 0)
            delay += random.applyAsLong(2 * jitterMillis + 1) - jitterMillis;
        return base.plusMillis(delay);
    }
}
