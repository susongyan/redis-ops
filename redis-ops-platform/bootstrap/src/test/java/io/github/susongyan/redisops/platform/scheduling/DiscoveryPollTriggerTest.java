package io.github.susongyan.redisops.platform.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.susongyan.redisops.platform.application.asset.AssetService;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

class DiscoveryPollTriggerTest {
    private final Instant now = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void drawsFreshJitterForEveryPollIncludingFirstPoll() {
        var calls = new AtomicInteger();
        var trigger = new DiscoveryPollTrigger(1000, 500, bound -> {
            assertEquals(1001, bound);
            return calls.getAndIncrement() == 0 ? 0 : bound - 1;
        });
        var context = new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(now.plusMillis(500), trigger.nextExecution(context));
        // A long-running execution must finish before the next delay starts.
        var completed = now.plusSeconds(20);
        context.update(now, now.plusSeconds(1), completed);
        assertEquals(completed.plusMillis(1500), trigger.nextExecution(context));
        assertEquals(2, calls.get());
    }

    @Test
    void zeroJitterRestoresFixedDelay() {
        var trigger = new DiscoveryPollTrigger(1000, 0, bound -> {
            fail("Random must not be sampled when jitter is disabled");
            return 0;
        });
        assertEquals(now.plusSeconds(1),
                trigger.nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC))));
    }

    @Test
    void rejectsInvalidOrOverflowingConfiguration() {
        for (long[] values : new long[][]{{0, 0}, {-1, 0}, {1000, -1}, {1000, 1000},
                {1000, 1001}, {Long.MAX_VALUE, 0}, {1000, Long.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class, () -> new DiscoveryPollTrigger(values[0], values[1]));
        }
        assertDoesNotThrow(() -> new DiscoveryPollTrigger(1, 0));
    }

    @Test
    void registersOneTriggerAndPreservesClaimTypeAndLease() {
        var jobs = mock(JobRepository.class);
        when(jobs.claimNext(anyString(), anyString(), any())).thenReturn(Optional.empty());
        var worker = new DiscoveryJobWorker(jobs, mock(AssetService.class), "test-platform", 1000, 500);
        var registrar = new ScheduledTaskRegistrar();
        try {
            worker.configureTasks(registrar);
            assertEquals(1, registrar.getTriggerTaskList().size());
            assertTrue(registrar.getFixedDelayTaskList().isEmpty());
            var task = registrar.getTriggerTaskList().get(0);
            assertInstanceOf(DiscoveryPollTrigger.class, task.getTrigger());
            task.getRunnable().run();
            verify(jobs).claimNext(eq("CLUSTER_DISCOVERY"), startsWith("test-platform-"), eq(Duration.ofSeconds(30)));
        } finally {
            registrar.destroy();
        }
    }
}
