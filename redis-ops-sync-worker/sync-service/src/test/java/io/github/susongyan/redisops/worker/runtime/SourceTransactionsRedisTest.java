package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Captures actual committed replication effects, never script source text from a log or MONITOR. */
class SourceTransactionsRedisTest {
    @Test
    void redis62PropagatesTransactionsAndLuaAsCompleteEffectUnits() throws Exception {
        capture("SYNC_BATCH_TEST_REDIS");
    }
    @Test
    void redis7ClusterPropagatesTransactionsAndLuaAsCompleteEffectUnits() throws Exception {
        capture("SYNC_BATCH_TEST_CLUSTER_SLOT0");
    }
    private void capture(String variable) throws Exception {
        String endpoint = System.getenv(variable);
        assumeTrue(endpoint != null && !endpoint.isBlank(), "requires disposable Redis");
        String[] parts = endpoint.split(":");
        String prefix = "sync-effects:" + UUID.randomUUID() + ":";
        String tag = "{" + ClusterSlotKeyspace.tag(0) + "}:";
        String a = tag + prefix + "a", b = tag + prefix + "b";
        assertEquals(0, RedisSlot.of(a));
        try (Socket replication = new Socket(parts[0], Integer.parseInt(parts[1]));
                Socket client = new Socket(parts[0], Integer.parseInt(parts[1]))) {
            replication.setSoTimeout(20_000);
            client.setSoTimeout(3000);
            var commands = new RespCodec(client.getInputStream(), client.getOutputStream());
            var input = new BufferedInputStream(replication.getInputStream());
            new RespCodec(input, replication.getOutputStream()).writeCommand("SYNC");
            String header;
            do {
                header = line(input);
            } while (header.isEmpty());
            assertTrue(header.startsWith("$"), "expected bounded length RDB transfer");
            long length = Long.parseLong(header.substring(1));
            assertTrue(length > 0 && length < 16L * 1024 * 1024, "fixture RDB must stay bounded");
            input.skipNBytes(length);
            var counting = new CountingInputStream(input);
            var reader = new ReplicationCommandReader(new RespCodec(counting, OutputStream.nullOutputStream()),
                    counting, 0);
            call(commands, "MULTI");
            call(commands, "SET", a, "1");
            call(commands, "SET", b, "2");
            call(commands, "EXEC");
            call(commands, "EVAL", "redis.call('INCR',KEYS[1]); redis.call('INCR',KEYS[2]); return 1", "2", a, b);
            var assembler = new SourceTransactionAssembler();
            List<SourceTransactionAssembler.Unit> transactions = new ArrayList<>();
            while (transactions.size() < 2) {
                var unit = assembler.accept(reader.read());
                if (unit.isPresent() && unit.get().transaction())
                    transactions.add(unit.get());
            }
            assertEquals(List.of("SET", "SET"),
                    transactions.get(0).commands().stream().map(ReplicationCommand::name)
                            .filter(n -> !n.equals("SELECT")).toList());
            assertEquals(List.of("INCR", "INCR"),
                    transactions.get(1).commands().stream().map(ReplicationCommand::name)
                            .filter(n -> !n.equals("SELECT")).toList());
            var policy = new io.github.susongyan.redisops.sync.contract.SyncCommandPolicy(false, true, Set.of(), "v3");
            var planner = new SourceTransactionPlanner(new KeyFilter(List.of(), List.of()), true, policy, 0);
            for (var transaction : transactions) {
                var plan = planner.plan(transaction, 0);
                assertEquals(CommandPlan.Disposition.APPLY, plan.plan().disposition());
                assertEquals(2, plan.plan().commands().size());
                assertEquals(0, plan.sourceDatabase());
            }
            assertFalse(assembler.hasOpenTransaction());
            assertTrue(transactions.get(0).endOffset() < transactions.get(1).startOffset());
            // Rebuild only these isolated fixture keys from captured effects and verify confirmed replay is a no-op.
            call(commands, "DEL", a, b);
            boolean cluster = variable.equals("SYNC_BATCH_TEST_CLUSTER_SLOT0");
            long taskId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            var profile = new WorkerRedisConnectionProfile(1,
                    cluster ? WorkerClusterMode.CLUSTER : WorkerClusterMode.STANDALONE,
                    List.of(endpoint), null, null, "NONE", null);
            var guard = new LeaseGuard(Duration.ZERO);
            guard.grant(Duration.ofMinutes(1));
            var fence = new TargetFence("effects", 1, "runtime", "fixture", Instant.now());
            try (var target = cluster
                    ? TargetCommandSession.clusterSlot(profile, RedisEndpoint.parse(endpoint), taskId,
                            Duration.ofSeconds(2), "effects", 0)
                    : new TargetCommandSession(profile, 0, taskId, Duration.ofSeconds(2))) {
                target.publishFence(fence, guard);
                for (var unit : transactions) {
                    var plan = new SourceTransactionPlanner(new KeyFilter(List.of(), List.of()), cluster, policy, 0)
                            .plan(unit, 0);
                    var checkpoint = new TargetCheckpoint("effects", 1, "source", unit.endOffset(),
                            plan.sourceDatabase(), Instant.now());
                    target.apply(plan.plan().commands(), checkpoint, fence, guard);
                    target.apply(plan.plan().commands(), checkpoint, fence, guard);
                    assertEquals(unit.endOffset(), target.checkpoint().orElseThrow().appliedOffset());
                }
                commands.writeCommand("GET", a);
                assertArrayEquals("2".getBytes(StandardCharsets.US_ASCII), ((RespValue.Bulk) commands.read()).value());
                commands.writeCommand("GET", b);
                assertArrayEquals("3".getBytes(StandardCharsets.US_ASCII), ((RespValue.Bulk) commands.read()).value());
            }
            call(commands, "DEL", a, b);
        }
    }
    private void call(RespCodec codec, String... args) throws IOException {
        codec.writeCommand(args);
        assertFalse(codec.read() instanceof RespValue.Error, "fixture command failed");
    }
    private String line(InputStream input) throws IOException {
        var result = new ByteArrayOutputStream();
        while (result.size() < 128) {
            int b = input.read();
            if (b < 0)
                throw new EOFException();
            if (b == '\n')
                return result.toString(StandardCharsets.US_ASCII).strip();
            result.write(b);
        }
        throw new IOException("invalid fixture replication header");
    }
}
