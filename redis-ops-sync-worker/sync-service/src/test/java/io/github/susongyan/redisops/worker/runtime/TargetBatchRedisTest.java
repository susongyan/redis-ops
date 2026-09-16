package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import io.github.susongyan.redisops.worker.runtime.TargetCommandSession.FencingException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: requires a disposable Redis. Uses unique keys; never FLUSHes any database. */
class TargetBatchRedisTest {
    @Test
    void v2FirstBatchMatchesNativeRedisEffects() throws Exception {
        record Case(String[] command, List<String[]> setup) {
        }
        List<String[]> strings = List.of(new String[]{"SET", "@a", "ab"}, new String[]{"SET", "@b", "cd"});
        List<String[]> sets = List.of(new String[]{"SADD", "@a", "1", "2"}, new String[]{"SADD", "@b", "2", "3"});
        List<String[]> lists = List.of(new String[]{"RPUSH", "@a", "1", "2"}, new String[]{"RPUSH", "@b", "3"});
        List<String[]> zsets = List.of(new String[]{"ZADD", "@a", "1", "x", "2", "y"},
                new String[]{"ZADD", "@b", "3", "y", "4", "z"});
        var cases = List.of(
                new Case(new String[]{"MSETNX", "@a", "1", "@b", "2"}, List.of()),
                new Case(new String[]{"RENAME", "@a", "@b"}, strings),
                new Case(new String[]{"RENAMENX", "@a", "@out"}, strings),
                new Case(new String[]{"SMOVE", "@a", "@b", "1"}, sets),
                new Case(new String[]{"LMOVE", "@a", "@b", "RIGHT", "LEFT"}, lists),
                new Case(new String[]{"RPOPLPUSH", "@a", "@b"}, lists),
                new Case(new String[]{"COPY", "@a", "@out", "DB", "0", "REPLACE"}, strings),
                new Case(new String[]{"BITOP", "XOR", "@out", "@a", "@b"}, strings),
                new Case(new String[]{"SUNIONSTORE", "@out", "@a", "@b"}, sets),
                new Case(new String[]{"SINTERSTORE", "@out", "@a", "@b"}, sets),
                new Case(new String[]{"SDIFFSTORE", "@out", "@a", "@b"}, sets),
                new Case(new String[]{"ZUNIONSTORE", "@out", "2", "@a", "@b", "WEIGHTS", "2", "3", "AGGREGATE", "MAX"},
                        zsets),
                new Case(new String[]{"ZINTERSTORE", "@out", "2", "@a", "@b"}, zsets),
                new Case(new String[]{"ZDIFFSTORE", "@out", "2", "@a", "@b"}, zsets),
                new Case(new String[]{"PFMERGE", "@out", "@a", "@b"},
                        List.of(new String[]{"PFADD", "@a", "1", "2"}, new String[]{"PFADD", "@b", "2", "3"})));
        var policy = new io.github.susongyan.redisops.sync.contract.SyncCommandPolicy(false, true, Set.of(), "v2");
        boolean cluster = endpointVariable().equals("SYNC_BATCH_TEST_CLUSTER_SLOT0");
        var planner = new CommandPlanner(new KeyFilter(List.of(), List.of()), cluster, null, policy);
        try (var target = session()) {
            target.requireMultiKeyVersion();
            target.publishFence(fence, guard);
            int offset = 0;
            for (var fixture : cases) {
                String caseId = fixture.command()[0];
                for (var setup : fixture.setup()) {
                    assertFalse(call(fixtureArgs(setup, caseId + ":native")).getClass().equals(RespValue.Error.class));
                    assertFalse(call(fixtureArgs(setup, caseId + ":planned")).getClass().equals(RespValue.Error.class));
                }
                assertFalse(call(fixtureArgs(fixture.command(), caseId + ":native")) instanceof RespValue.Error,
                        caseId);
                var args = fixtureArgs(fixture.command(), caseId + ":planned");
                var plan = planner.plan(new ReplicationCommand(args[0], Arrays.stream(args)
                        .map(s -> s.getBytes(StandardCharsets.UTF_8)).toList(), offset, ++offset));
                assertEquals(CommandPlan.Disposition.APPLY, plan.disposition(), caseId);
                target.apply(plan.commands(), new TargetCheckpoint("epoch", 1, "repl", offset, 0, Instant.now()), fence,
                        guard);
                for (String suffix : List.of("a", "b", "out")) {
                    var expected = call("DUMP", key(caseId + ":native:" + suffix));
                    var actual = call("DUMP", key(caseId + ":planned:" + suffix));
                    if (expected == RespValue.NullValue.INSTANCE)
                        assertEquals(expected, actual, caseId);
                    else
                        assertArrayEquals(((RespValue.Bulk) expected).value(), ((RespValue.Bulk) actual).value(),
                                caseId);
                }
            }
        }
    }
    private String[] fixtureArgs(String[] args, String prefix) {
        return Arrays.stream(args).map(arg -> arg.startsWith("@") ? key(prefix + ":" + arg.substring(1)) : arg)
                .toArray(String[]::new);
    }
    @Test
    void queueTimeErrorClosesTransactionWithoutApplyingEarlierQueuedCommands() throws Exception {
        try (var target = session()) {
            target.publishFence(fence, guard);
            var blocked = assertThrows(SyncBlockedException.class,
                    () -> target.apply(List.of(planned("INCR", key("counter")), planned("INCR")), next(), fence,
                            guard));
            assertEquals("BLOCKED_TARGET_BATCH_UNCONFIRMED", blocked.reason());
        }
        assertEquals(RespValue.NullValue.INSTANCE, call("GET", key("counter")));
        try (var restarted = session()) {
            assertThrows(SyncBlockedException.class, () -> restarted.checkpoint());
        }
    }
    @Test
    void keyParserMatchesRedisCommandGetKeysForFirstBatch() throws Exception {
        List<List<String>> commands = List.of(
                List.of("DEL", "a", "b"), List.of("UNLINK", "a", "b"),
                List.of("MSET", "a", "1", "b", "2"), List.of("MSETNX", "a", "1", "b", "2"),
                List.of("RENAME", "a", "b"), List.of("RENAMENX", "a", "b"),
                List.of("SMOVE", "a", "b", "member"), List.of("LMOVE", "a", "b", "RIGHT", "LEFT"),
                List.of("RPOPLPUSH", "a", "b"), List.of("COPY", "a", "b", "DB", "0", "REPLACE"),
                List.of("BITOP", "AND", "out", "a", "b"), List.of("BITOP", "NOT", "out", "a"),
                List.of("SUNIONSTORE", "out", "a", "b"), List.of("SINTERSTORE", "out", "a", "b"),
                List.of("SDIFFSTORE", "out", "a", "b"), List.of("PFMERGE", "out", "a", "b"),
                List.of("ZUNIONSTORE", "out", "2", "a", "b", "WEIGHTS", "2", "3", "AGGREGATE", "MAX"),
                List.of("ZINTERSTORE", "out", "2", "a", "b", "AGGREGATE", "MIN", "WEIGHTS", "-1", "2"),
                List.of("ZDIFFSTORE", "out", "2", "a", "b"), List.of("XGROUP", "CREATE", "a", "group", "0"));
        for (var args : commands) {
            var query = new ArrayList<>(List.of("COMMAND", "GETKEYS"));
            query.addAll(args);
            var actual = (RespValue.Array) call(query.toArray(String[]::new));
            var encoded = args.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
            var parsed = CommandKeySemantics.describe(args.get(0), encoded).orElseThrow();
            assertEquals(parsed.keys().size(), actual.values().size(), args.get(0));
            // Redis 6.2's movable-key callbacks and 7.x key specs can order results differently.
            var expectedKeys = parsed.keys().stream().map(k -> HexFormat.of().formatHex(encoded.get(k.index())))
                    .sorted().toList();
            var actualKeys = actual.values().stream().map(v -> HexFormat.of().formatHex(((RespValue.Bulk) v).value()))
                    .sorted().toList();
            assertEquals(expectedKeys, actualKeys, args.get(0));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void takeoverBetweenWatchAndExecRevokesStaleWriter(int selectedExec) throws Exception {
        try (var direct = session(); var takeover = session()) {
            direct.publishFence(fence, guard);
            String actualEndpoint = endpoint;
            var newer = new TargetFence("epoch", 2, "new-runtime", "worker", Instant.now());
            try (var proxy = new DropExecReplyProxy(Integer.parseInt(endpoint.split(":")[1]), selectedExec, () -> {
                try {
                    takeover.publishFence(newer, guard);
                    assertEquals(1, selectedExec);
                } catch (SyncBlockedException pending) {
                    assertTrue(selectedExec > 1);
                    assertEquals("BLOCKED_TARGET_BATCH_UNCONFIRMED", pending.reason());
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            })) {
                endpoint = "127.0.0.1:" + proxy.port();
                try (var stale = session()) {
                    if (selectedExec == 1)
                        assertThrows(FencingException.class,
                                () -> stale.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
                    else
                        assertThrows(SyncBlockedException.class,
                                () -> stale.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
                }
            } finally {
                endpoint = actualEndpoint;
            }
            assertEquals(2, takeover.currentFence().orElseThrow().generation());
            if (selectedExec == 1)
                assertTrue(direct.checkpoint().isEmpty());
            else
                assertThrows(SyncBlockedException.class, () -> direct.checkpoint());
        }
        var value = call("GET", key("counter"));
        if (selectedExec < 3)
            assertEquals(RespValue.NullValue.INSTANCE, value);
        else
            assertEquals("1", new String(((RespValue.Bulk) value).value(), StandardCharsets.UTF_8));
    }
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
    String endpoint;
    long taskId;
    private TargetFence fence;
    private LeaseGuard guard;

    @BeforeEach
    void setup() {
        endpoint = System.getenv(endpointVariable());
        Assumptions.assumeTrue(endpoint != null && endpoint.startsWith("127.0.0.1:"));
        taskId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());
        guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofMinutes(2));
    }
    String endpointVariable() {
        return "SYNC_BATCH_TEST_REDIS";
    }
    String checkpointKey() {
        return "__redis_ops_sync_ckpt__:{" + taskId + "}:standalone";
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
            assertEquals(2, takeover.currentFence().orElseThrow().generation());
            assertThrows(SyncBlockedException.class, () -> takeover.checkpoint());
        }
        try (var stale = session()) {
            assertThrows(FencingException.class,
                    () -> stale.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
        }
        assertEquals("1", new String(((RespValue.Bulk) call("GET", key("counter"))).value(), StandardCharsets.UTF_8));
    }
    @Test
    void preexistingPendingFromCrashedProcessBlocksBeforeSendingEffects() throws Exception {
        try (var session = session()) {
            session.publishFence(fence, guard);
        }
        call("SET", checkpointKey(),
                new String(PendingTargetBatch.encode(null, next()), StandardCharsets.US_ASCII));
        try (var session = session()) {
            assertThrows(SyncBlockedException.class,
                    () -> session.apply(List.of(planned("INCR", key("counter"))), next(), fence, guard));
        }
        assertEquals(RespValue.NullValue.INSTANCE, call("GET", key("counter")));
    }
}
