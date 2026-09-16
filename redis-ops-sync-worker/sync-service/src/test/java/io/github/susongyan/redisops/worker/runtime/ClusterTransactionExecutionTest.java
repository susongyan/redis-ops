package io.github.susongyan.redisops.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncExecutionPort;
import io.github.susongyan.redisops.worker.protocol.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterTransactionExecutionTest {
    @Test
    void sourceTransactionAcrossBatchesIsOneRoutedCommit(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.apply(command(1, "MULTI"), command(2, "INCR", "{biz}:a"));
            verifyNoInteractions(fixture.target);
            fixture.apply(command(3, "INCR", "{biz}:b"), command(4, "EXEC"));
            var checkpoint = ArgumentCaptor.forClass(TargetCheckpoint.class);
            verify(fixture.target).apply(argThat(commands -> commands.size() == 2
                    && commands.stream().allMatch(c -> c.slot() == RedisSlot.of("{biz}:a"))), checkpoint.capture(),
                    any(), any());
            assertEquals(4, checkpoint.getValue().appliedOffset());
        }
    }
    @Test
    void crossSlotTransactionIsBlockedBeforeAnyRouting(@TempDir Path directory) throws Exception {
        try (var fixture = new Fixture(directory)) {
            var blocked = assertThrows(SyncBlockedException.class, () -> fixture.apply(command(1, "MULTI"),
                    command(2, "INCR", "{a}:x"), command(3, "INCR", "{b}:x"), command(4, "EXEC")));
            assertEquals("BLOCKED_TRANSACTION_CROSS_SLOT", blocked.reason());
            verifyNoInteractions(fixture.target);
        }
    }
    private ReplicationCommand command(long offset, String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList(), offset, offset);
    }
    private static final class Fixture implements AutoCloseable {
        final ClusterTargetRouter target = mock(ClusterTargetRouter.class,
                withSettings().mockMaker("mock-maker-inline"));
        final Object channel;
        Fixture(Path directory) throws Exception {
            var task = mock(WorkerSyncTask.class, withSettings().mockMaker("mock-maker-inline"));
            when(task.id()).thenReturn(17L);
            when(task.fullSyncEpoch()).thenReturn("test-epoch");
            when(task.commandPolicyJson()).thenReturn("{\"policyVersion\":\"v3\",\"allowSafeSplit\":true}");
            var keys = mock(SpoolKeyProvider.class);
            when(keys.taskKey(17)).thenReturn(new byte[32]);
            var runner = new ClusterSyncTaskRunner(task, false, null, mock(WorkerSyncExecutionPort.class),
                    mock(SyncRunnerStateReporter.class), keys, null, new ObjectMapper(), directory, 1024,
                    Duration.ofSeconds(1), 1, 1, 1, 1024, Duration.ZERO, Duration.ofSeconds(1));
            var profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.CLUSTER, List.of("127.0.0.1:6379"),
                    null, null, "NONE", null);
            ReflectionTestUtils.setField(runner, "sourceProfile", profile);
            ReflectionTestUtils.setField(runner, "targetProfile", profile);
            ((LeaseGuard) ReflectionTestUtils.getField(runner, "leaseGuard")).grant(Duration.ofMinutes(1));
            Class<?> specClass = Class.forName(ClusterSyncTaskRunner.class.getName() + "$SourceSpec");
            var specConstructor = specClass.getDeclaredConstructors()[0];
            specConstructor.setAccessible(true);
            var slots = new BitSet(16384);
            slots.set(0, 16384);
            Object spec = specConstructor.newInstance("test", new RedisEndpoint("127.0.0.1", 6379), slots);
            Class<?> channelClass = Class.forName(ClusterSyncTaskRunner.class.getName() + "$Channel");
            var constructor = channelClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            channel = constructor.newInstance(runner, spec, new KeyFilter(List.of(), List.of()), 1024L * 1024, 1, 1);
            ReflectionTestUtils.setField(channel, "routedTarget", target);
            ReflectionTestUtils.setField(channel, "spool",
                    mock(EncryptedSpool.class, withSettings().mockMaker("mock-maker-inline")));
        }
        void apply(ReplicationCommand... commands) {
            ReflectionTestUtils.invokeMethod(channel, "applyBatch", List.of(commands));
        }
        public void close() {
            ReflectionTestUtils.invokeMethod(channel, "close");
        }
    }
}
