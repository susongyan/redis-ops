package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local comparison, not a production sizing guarantee. Never runs the legacy path in application code. */
class TargetBatchThroughputRedisTest {
    @Test
    void compareConfirmationWithLegacySuccessOnlyTransaction() throws Exception {
        String endpoint = System.getenv("SYNC_BATCH_BENCHMARK_REDIS");
        Assumptions.assumeTrue(endpoint != null && endpoint.startsWith("127.0.0.1:"));
        for (int size : new int[]{1, 100}) {
            for (int round = 0; round < 3; round++) {
                // Alternate order to reduce warm-up/order bias.
                long confirmed, legacy;
                if (round % 2 == 0) {
                    legacy = run(endpoint, size, false);
                    confirmed = run(endpoint, size, true);
                } else {
                    confirmed = run(endpoint, size, true);
                    legacy = run(endpoint, size, false);
                }
                System.out.printf(Locale.ROOT,
                        "BATCH_BENCH size=%d round=%d batches=100 legacy_ms=%.3f confirmed_ms=%.3f ratio=%.3f%n",
                        size, round + 1, legacy / 1e6, confirmed / 1e6, (double) confirmed / legacy);
            }
        }
    }

    private long run(String endpoint, int size, boolean confirmed) throws Exception {
        long taskId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        byte[] counter = bytes("batch-benchmark:" + taskId);
        byte[] checkpoint = bytes("__redis_ops_sync_ckpt__:{" + taskId + "}:standalone");
        byte[] fenceKey = bytes("__redis_ops_sync_fence__:{" + taskId + "}:standalone");
        var profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.STANDALONE,
                List.of(endpoint), null, null, "NONE", null);
        var fence = new TargetFence("benchmark", 1, "fixture", "worker", Instant.now());
        var guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofMinutes(2));
        var operation = new CommandPlan.PlannedCommand(-1, List.of(bytes("INCR"), counter));
        var batch = Collections.nCopies(size, operation);
        var address = RedisEndpoint.parse(endpoint);
        try (var target = new TargetCommandSession(profile, 0, taskId, Duration.ofSeconds(2));
                var socket = new Socket(address.host(), address.port())) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(3000);
            var codec = new RespCodec(new BufferedInputStream(socket.getInputStream(), 65536),
                    new BufferedOutputStream(socket.getOutputStream(), 65536));
            target.publishFence(fence, guard);
            long start = 0;
            for (int i = 0; i < 120; i++) {
                if (i == 20)
                    start = System.nanoTime();
                var next = new TargetCheckpoint("benchmark", 1, "repl", (long) (i + 1) * size, 0, Instant.now());
                if (confirmed)
                    target.apply(batch, next, fence, guard);
                else {
                    // Reproduce the former success-only path solely for a bounded performance comparison.
                    command(codec, bytes("WATCH"), fenceKey, checkpoint);
                    command(codec, bytes("GET"), fenceKey);
                    command(codec, bytes("GET"), checkpoint);
                    command(codec, bytes("MULTI"));
                    for (int n = 0; n < size; n++)
                        codec.writeCommandBuffered(bytes("INCR"), counter);
                    codec.writeCommandBuffered(bytes("SET"), checkpoint, next.encode());
                    codec.flush();
                    for (int n = 0; n <= size; n++)
                        assertEquals(new RespValue.Simple("QUEUED"), codec.read());
                    var replies = (RespValue.Array) command(codec, bytes("EXEC"));
                    assertEquals(size + 1, replies.values().size());
                    assertTrue(replies.values().stream().noneMatch(RespValue.Error.class::isInstance));
                }
            }
            long elapsed = System.nanoTime() - start;
            var count = (RespValue.Bulk) command(codec, bytes("GET"), counter);
            assertEquals(Integer.toString(120 * size), new String(count.value(), StandardCharsets.US_ASCII));
            assertEquals(120L * size, target.checkpoint().orElseThrow().appliedOffset());
            return elapsed;
        }
    }
    private RespValue command(RespCodec codec, byte[]... args) throws IOException {
        codec.writeCommand(args);
        var reply = codec.read();
        assertFalse(reply instanceof RespValue.Error);
        return reply;
    }
    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
