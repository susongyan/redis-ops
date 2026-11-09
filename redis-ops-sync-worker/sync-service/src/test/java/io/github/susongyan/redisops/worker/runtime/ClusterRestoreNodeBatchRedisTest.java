package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.susongyan.redisops.worker.runtime.TargetCommandSession.FencingException;
import io.github.susongyan.redisops.worker.runtime.TargetCommandSession.RestoreRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opt-in real-server test for the pipelined per-slot node batch; never FLUSHes anything and only
 * touches {@code node-batch-fixture:} keys of this run's task id. Requires
 * {@code SYNC_BATCH_TEST_CLUSTER_SLOT0} to point at a local dev cluster node.
 */
class ClusterRestoreNodeBatchRedisTest {
    private static final String ENDPOINT_ENV = "SYNC_BATCH_TEST_CLUSTER_SLOT0";
    private static final String CHANNEL = "fixture";
    /** DUMP of the string "hello" (RDB 9, valid checksum) used as payload for every fixture key. */
    private static final byte[] HELLO_DUMP = Base64.getDecoder().decode("AAVoZWxsbwkAs4COujGyQ7s=");
    private TargetCommandSession target;
    private TargetFence fence;
    private LeaseGuard guard;
    private long taskId;
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        String endpoint = System.getenv(ENDPOINT_ENV);
        org.junit.jupiter.api.Assumptions.assumeTrue(endpoint != null && endpoint.startsWith("127.0.0.1:"));
        taskId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());
        guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofMinutes(2));
        var profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.CLUSTER, List.of(endpoint),
                null, null, "NONE", null);
        target = TargetCommandSession.clusterSlot(profile, RedisEndpoint.parse(endpoint), taskId,
                Duration.ofSeconds(2), Duration.ofSeconds(10), CHANNEL, 0);
    }

    @AfterEach
    void cleanup() {
        try {
            if (target != null) {
                target.deleteOwnReservedKeys();
                redisCli(createdKeys.toArray(String[]::new));
            }
        } catch (Exception ignored) {
            // best effort cleanup of this run's fixture keys only
        }
    }

    /** Keys are hash-tagged with the per-slot tag so each genuinely lands in the wanted slot. */
    private String key(int slot, String suffix) {
        String key = "node-batch-fixture:{" + ClusterSlotKeyspace.tag(slot) + "}:" + taskId + ":" + suffix;
        createdKeys.add(key);
        return key;
    }

    private RestoreRequest request(int slot, String suffix) {
        return new RestoreRequest(key(slot, suffix).getBytes(StandardCharsets.US_ASCII), -1, HELLO_DUMP);
    }

    private void publishFence(int slot) throws Exception {
        target.publishFence(fence, guard, taskId, CHANNEL, slot);
        createdKeys.add(new String(ClusterSlotKeyspace.fence(taskId, CHANNEL, slot), StandardCharsets.US_ASCII));
    }

    private static void redisCli(String... args) {
        if (args.length == 0)
            return;
        try {
            var command = new java.util.ArrayList<String>();
            command.add("/home/pc/software/redis/bin/redis-cli");
            command.add("-c");
            command.add("-p");
            command.add("7101");
            command.add("DEL");
            command.addAll(List.of(args));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception error) {
            throw new IllegalStateException("redis-cli invocation failed", error);
        }
    }

    private static String redisCliGet(String key) {
        try {
            var process = new ProcessBuilder("/home/pc/software/redis/bin/redis-cli", "-c", "-p", "7101",
                    "GET", key).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            process.waitFor();
            return output.trim();
        } catch (Exception error) {
            throw new IllegalStateException("redis-cli invocation failed", error);
        }
    }

    @Test
    void pipelinedNodeBatchAppliesAllKeysAndValidatesFence() throws Exception {
        publishFence(0);
        target.restoreNodeBatch(List.of(request(0, "a"), request(0, "b"), request(0, "c")),
                fence, 0, guard, taskId, CHANNEL);
        for (String suffix : List.of("a", "b", "c"))
            assertEquals("hello", redisCliGet(key(0, suffix)),
                    "key " + suffix + " must hold the restored value");
    }

    @Test
    void multiSlotNodeBatchAcrossManySlotsStaysInSync() throws Exception {
        // 7101 owns slots 0..5460, so all three slots live on the same node but differ per command.
        List<Integer> slots = List.of(0, 1234, 5460);
        for (int slot : slots)
            publishFence(slot);
        target.restoreNodeBatch(
                List.of(request(0, "s0"), request(1234, "s1234"), request(5460, "s5460")),
                fence, 0, guard, taskId, CHANNEL);
        assertEquals("hello", redisCliGet(key(0, "s0")));
        assertEquals("hello", redisCliGet(key(1234, "s1234")));
        assertEquals("hello", redisCliGet(key(5460, "s5460")));
    }

    @Test
    void largeNodeBatchWith200SlotsStaysInSync() throws Exception {
        for (int slot = 0; slot < 200; slot++)
            publishFence(slot);
        List<RestoreRequest> requests = new ArrayList<>();
        for (int slot = 0; slot < 200; slot++)
            requests.add(request(slot, "k" + slot));
        target.restoreNodeBatch(requests, fence, 0, guard, taskId, CHANNEL);
        assertEquals("hello", redisCliGet(key(199, "k199")));
        assertEquals("hello", redisCliGet(key(0, "k0")));
    }

    @Test
    void newerFenceAbortsTheWholeNodeBatch() throws Exception {
        publishFence(0);
        target.publishFence(new TargetFence("epoch", 2, "other-runtime", "worker", Instant.now()), guard);
        assertThrows(FencingException.class, () -> target.restoreNodeBatch(
                List.of(request(0, "x")), fence, 0, guard, taskId, CHANNEL));
        assertTrue(!"hello".equals(redisCliGet(key(0, "x"))),
                "aborted batch must not have applied the key");
    }
}
