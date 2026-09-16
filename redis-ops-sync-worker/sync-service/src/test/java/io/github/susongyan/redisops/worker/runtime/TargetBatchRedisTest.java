package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: requires a disposable Redis. Uses unique keys; never FLUSHes any database. */
class TargetBatchRedisTest {
    @Test
    void destructiveCommandCannotEraseRecoveryState() throws Exception {
        try (var session = session()) {
            session.publishFence(fence, guard);
            var error = assertThrows(SyncBlockedException.class,
                    () -> session.apply(List.of(planned("FLUSHDB")), next(), fence, guard));
            assertEquals("BLOCKED_DESTRUCTIVE_BATCH_CONFIRMATION", error.reason());
            assertTrue(session.checkpoint().isEmpty());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void lostExecRepliesNeverCauseBlindReplay(int droppedExec) throws Exception {
        try (var direct = session()) {
            direct.publishFence(fence, guard);
        }
        var next = next();
        String actualEndpoint = endpoint;
        try (var proxy = new DropExecReplyProxy(Integer.parseInt(endpoint.split(":")[1]), droppedExec, false)) {
            endpoint = "127.0.0.1:" + proxy.port();
            try (var proxied = session()) {
                assertThrows(Exception.class,
                        () -> proxied.apply(List.of(planned("INCR", key("counter"))), next, fence, guard));
            }
        } finally {
            endpoint = actualEndpoint;
        }
        try (var restarted = session()) {
            if (droppedExec < 3)
                assertThrows(SyncBlockedException.class,
                        () -> restarted.apply(List.of(planned("INCR", key("counter"))), next, fence, guard));
            else
                restarted.apply(List.of(planned("INCR", key("counter"))), next, fence, guard);
        }
        var value = call("GET", key("counter"));
        if (droppedExec == 1)
            assertEquals(RespValue.NullValue.INSTANCE, value);
        else
            assertEquals("1", new String(((RespValue.Bulk) value).value(), StandardCharsets.UTF_8));
    }
    @Test
    void crashBeforeBusinessExecLeavesNoEffectsButStillRequiresReconciliation() throws Exception {
        try (var direct = session()) {
            direct.publishFence(fence, guard);
        }
        String actualEndpoint = endpoint;
        try (var proxy = new DropExecReplyProxy(Integer.parseInt(endpoint.split(":")[1]), 2, true)) {
            endpoint = "127.0.0.1:" + proxy.port();
            try (var proxied = session()) {
                assertThrows(SyncBlockedException.class,
                        () -> proxied.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
            }
        } finally {
            endpoint = actualEndpoint;
        }
        try (var restarted = session()) {
            assertThrows(SyncBlockedException.class, () -> restarted.checkpoint());
        }
        assertEquals(RespValue.NullValue.INSTANCE, call("GET", key("counter")));
    }
    private String endpoint;
    private long taskId;
    private TargetFence fence;
    private LeaseGuard guard;

    @BeforeEach
    void setup() {
        endpoint = System.getenv("SYNC_BATCH_TEST_REDIS");
        Assumptions.assumeTrue(endpoint != null && endpoint.startsWith("127.0.0.1:"));
        taskId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());
        guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofMinutes(2));
    }
    TargetCommandSession session() throws Exception {
        return new TargetCommandSession(new WorkerRedisConnectionProfile(1, WorkerClusterMode.STANDALONE,
                List.of(endpoint), null, null, "NONE", null), 0, taskId, Duration.ofSeconds(2));
    }
    String key(String suffix) {
        return "batch-fixture:" + taskId + ":" + suffix;
    }
    TargetCheckpoint next() {
        return new TargetCheckpoint("epoch", 1, "repl", 100, 0, Instant.now());
    }
    CommandPlan.PlannedCommand planned(String... args) {
        return new CommandPlan.PlannedCommand(-1,
                Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList());
    }
    RespValue call(String... args) throws Exception {
        try (var socket = new Socket("127.0.0.1", Integer.parseInt(endpoint.split(":")[1]))) {
            var codec = new RespCodec(socket.getInputStream(), socket.getOutputStream());
            codec.writeCommand(args);
            return codec.read();
        }
    }
    @Test
    void successAndConfirmedReplayApplyNonIdempotentCommandOnce() throws Exception {
        var next = next();
        try (var session = session()) {
            session.publishFence(fence, guard);
            session.apply(List.of(planned("INCR", key("counter"))), next, fence, guard);
            session.apply(List.of(planned("INCR", key("counter"))), next, fence, guard);
            assertEquals(next.appliedOffset(), session.checkpoint().orElseThrow().appliedOffset());
        }
        assertEquals("1", new String(((RespValue.Bulk) call("GET", key("counter"))).value(), StandardCharsets.UTF_8));
    }
    @Test
    void partialExecErrorBlocksReplayAndTakeoverEvenWhenOtherCommandsSucceeded() throws Exception {
        call("SET", key("wrongtype"), "not-an-integer");
        try (var session = session()) {
            session.publishFence(fence, guard);
            var error = assertThrows(SyncBlockedException.class, () -> session.apply(
                    List.of(planned("INCR", key("counter")), planned("INCR", key("wrongtype"))), next(), fence, guard));
            assertEquals("BLOCKED_TARGET_BATCH_UNCONFIRMED", error.reason());
        }
        try (var restarted = session()) {
            assertThrows(SyncBlockedException.class, () -> restarted.checkpoint());
            assertThrows(SyncBlockedException.class,
                    () -> restarted.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
        }
        try (var takeover = session()) {
            assertThrows(SyncBlockedException.class, () -> takeover.publishFence(
                    new TargetFence("epoch", 2, "new-runtime", "worker", Instant.now()), guard));
        }
        assertEquals("1", new String(((RespValue.Bulk) call("GET", key("counter"))).value(), StandardCharsets.UTF_8));
    }
    @Test
    void preexistingPendingFromCrashedProcessBlocksBeforeSendingEffects() throws Exception {
        try (var session = session()) {
            session.publishFence(fence, guard);
        }
        call("SET", "__redis_ops_sync_ckpt__:{" + taskId + "}:standalone",
                new String(PendingTargetBatch.encode(null, next()), StandardCharsets.US_ASCII));
        try (var session = session()) {
            assertThrows(SyncBlockedException.class,
                    () -> session.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
        }
        assertEquals(RespValue.NullValue.INSTANCE, call("GET", key("counter")));
    }
}
