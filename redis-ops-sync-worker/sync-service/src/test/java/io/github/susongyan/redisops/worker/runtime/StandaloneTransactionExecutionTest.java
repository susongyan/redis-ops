package io.github.susongyan.redisops.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.sync.contract.SyncCommandPolicy;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncExecutionPort;
import io.github.susongyan.redisops.worker.protocol.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandaloneTransactionExecutionTest {
    @Test
    void transactionCrossesSeveralHundredCommandBatchesWithoutIntermediateWrites() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.apply(command(1, "MULTI"));
            for (int batch = 0; batch < 3; batch++) {
                ReplicationCommand[] commands = new ReplicationCommand[100];
                for (int i = 0; i < 100; i++)
                    commands[i] = command(2L + batch * 100 + i, "INCR", "biz:a");
                fixture.apply(commands);
                verifyNoInteractions(fixture.target);
            }
            fixture.apply(command(302, "EXEC"));
            verify(fixture.target).apply(argThat(commands -> commands.size() == 300),
                    argThat(checkpoint -> checkpoint.appliedOffset() == 302), any(), any());
        }
    }

    @Test
    void targetIsNotCalledUntilExecAndCheckpointUsesWholeUnitBoundary() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.apply(command(1, "MULTI"), command(2, "INCR", "biz:a"));
            verifyNoInteractions(fixture.target);
            fixture.apply(command(3, "INCR", "biz:b"), command(4, "EXEC"));
            var checkpoint = ArgumentCaptor.forClass(TargetCheckpoint.class);
            verify(fixture.target).apply(argThat(commands -> commands.size() == 2), checkpoint.capture(), any(), any());
            assertEquals(4, checkpoint.getValue().appliedOffset());
            fixture.apply(command(1, "MULTI"), command(2, "INCR", "biz:a"), command(3, "INCR", "biz:b"),
                    command(4, "EXEC"));
            verifyNoMoreInteractions(fixture.target);
        }
    }
    @Test
    void mixedScopeTransactionNeverReachesTarget() throws Exception {
        try (var fixture = new Fixture()) {
            var blocked = assertThrows(SyncBlockedException.class, () -> fixture.apply(command(1, "MULTI"),
                    command(2, "INCR", "biz:a"), command(3, "INCR", "outside"), command(4, "EXEC")));
            assertEquals("BLOCKED_TRANSACTION_MIXED_SCOPE", blocked.reason());
            verifyNoInteractions(fixture.target);
        }
    }
    @Test
    void terminalIncompleteTransactionFailsClosed() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.apply(command(1, "MULTI"), command(2, "INCR", "biz:a"));
            var blocked = assertThrows(SyncBlockedException.class,
                    () -> ReflectionTestUtils.invokeMethod(fixture.runner, "assertCompleteTransactionInput"));
            assertEquals("BLOCKED_TRANSACTION_INCOMPLETE", blocked.reason());
            verifyNoInteractions(fixture.target);
        }
    }
    private ReplicationCommand command(long offset, String... args) {
        return new ReplicationCommand(args[0],
                Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList(), offset, offset);
    }
    private static final class Fixture implements AutoCloseable {
        final TargetCommandSession target = mock(TargetCommandSession.class,
                withSettings().mockMaker("mock-maker-inline"));
        final StandaloneSyncTaskRunner runner;
        Fixture() throws Exception {
            var task = mock(WorkerSyncTask.class, withSettings().mockMaker("mock-maker-inline"));
            when(task.id()).thenReturn(17L);
            when(task.fullSyncEpoch()).thenReturn("test-epoch");
            var sync = mock(WorkerSyncExecutionPort.class);
            runner = new StandaloneSyncTaskRunner(task, false, null, sync, mock(SyncRunnerStateReporter.class), null,
                    null, new ObjectMapper(), Path.of("unused"), 1024, Duration.ofSeconds(1), 1, 1, 1, 1024,
                    Duration.ZERO, Duration.ofSeconds(1));
            var filter = new KeyFilter(List.of("biz:*"), List.of());
            var policy = new SyncCommandPolicy(false, true, Set.of(), "v3");
            ReflectionTestUtils.setField(runner, "planner", new CommandPlanner(filter, false, null, policy));
            ReflectionTestUtils.setField(runner, "transactionBatcher", new SourceExecutionBatcher());
            ReflectionTestUtils.setField(runner, "transactionPlanner",
                    new SourceTransactionPlanner(filter, false, policy, 0));
            ReflectionTestUtils.setField(runner, "target", target);
            ReflectionTestUtils.setField(runner, "sourceEndpoint", new RedisEndpoint("127.0.0.1", 6379));
            ReflectionTestUtils.setField(runner, "spool",
                    mock(EncryptedSpool.class, withSettings().mockMaker("mock-maker-inline")));
            when(target.apply(anyList(), any(), any(), any())).thenAnswer(call -> call.getArgument(1));
        }
        void apply(ReplicationCommand... commands) {
            ReflectionTestUtils.invokeMethod(runner, "applyBatch", List.of(commands));
        }
        public void close() {
            runner.close();
        }
    }
}
