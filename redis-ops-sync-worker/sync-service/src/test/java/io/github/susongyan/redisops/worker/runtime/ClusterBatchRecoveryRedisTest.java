package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three disposable masters, listed in ascending slot range order. Only endpoint NAT is adapted. */
class ClusterBatchRecoveryRedisTest {
    private List<RedisEndpoint> nodes;
    private WorkerRedisConnectionProfile profile;
    private RedisDataEndpointResolver resolver;
    private long taskId;
    private LeaseGuard guard;
    private TargetFence fence;

    @BeforeEach
    void setup() throws Exception {
        String configured = System.getenv("SYNC_BATCH_TEST_CLUSTER_MASTERS");
        Assumptions.assumeTrue(configured != null);
        var seeds = List.of(configured.split(","));
        assertEquals(3, seeds.size());
        assertTrue(seeds.stream().allMatch(s -> s.startsWith("127.0.0.1:")));
        nodes = seeds.stream().map(RedisEndpoint::parse).toList();
        profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.CLUSTER, seeds, null, null, "NONE", null);
        resolver = new RedisDataEndpointResolver(2000) {
            @Override
            public List<ClusterMaster> resolveClusterMasters(WorkerRedisConnectionProfile connection)
                    throws java.io.IOException {
                var discovered = super.resolveClusterMasters(connection).stream()
                        .sorted(Comparator.comparingInt(ClusterMaster::slotStart)).toList();
                assertEquals(3, discovered.size());
                var translated = new ArrayList<ClusterMaster>();
                for (int i = 0; i < discovered.size(); i++) {
                    var master = discovered.get(i);
                    translated.add(
                            new ClusterMaster(nodes.get(i), master.nodeId(), master.slotStart(), master.slotEnd()));
                }
                return translated;
            }
        };
        taskId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        guard = new LeaseGuard(Duration.ZERO);
        guard.grant(Duration.ofMinutes(2));
        fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());
    }

    @Test
    void partialMasterFailureDoesNotAdvanceCursorOrReplaySuccessfulMaster() throws Exception {
        var masters = resolver.resolveClusterMasters(profile);
        int secondSlot = masters.get(1).slotStart();
        String firstKey = key(0, "counter");
        String secondKey = key(secondSlot, "counter");
        String invalidKey = key(secondSlot, "invalid");
        call(nodes.get(1), "SET", invalidKey, "not-integer");
        var batch = List.of(planned(0, "INCR", firstKey), planned(secondSlot, "INCR", secondKey),
                planned(secondSlot, "INCR", invalidKey));
        var next = checkpoint(100, 1);
        try (var router = router()) {
            router.publishFences(ClusterTargetRouter.allSlots(), fence, guard);
            router.initializeFullCheckpoint(ClusterTargetRouter.allSlots(), checkpoint(0, 1), fence, guard);
            assertThrows(SyncBlockedException.class, () -> router.apply(batch, next, fence, guard));
            assertEquals(0, router.checkpoint().orElseThrow().appliedOffset());
        }
        var newer = new TargetFence("epoch", 2, "replacement", "worker", Instant.now());
        try (var restarted = router()) {
            assertEquals(0, restarted.publishFences(ClusterTargetRouter.allSlots(), newer, guard).orElseThrow());
            var blocked = assertThrows(SyncBlockedException.class,
                    () -> restarted.apply(batch, checkpoint(100, 2), newer, guard));
            assertEquals("BLOCKED_TARGET_BATCH_UNCONFIRMED", blocked.reason());
            assertEquals(0, restarted.checkpoint().orElseThrow().appliedOffset());
        }
        assertEquals("1", value(call(nodes.get(0), "GET", firstKey)));
        assertEquals("1", value(call(nodes.get(1), "GET", secondKey)));
    }

    @Test
    void confirmedMultiMasterBatchIsDeduplicatedAfterTakeover() throws Exception {
        var masters = resolver.resolveClusterMasters(profile);
        var batch = masters.stream().map(m -> planned(m.slotStart(), "INCR", key(m.slotStart(), "counter"))).toList();
        try (var router = router()) {
            router.publishFences(ClusterTargetRouter.allSlots(), fence, guard);
            router.apply(batch, checkpoint(100, 1), fence, guard);
            assertEquals(100, router.checkpoint().orElseThrow().appliedOffset());
        }
        var newer = new TargetFence("epoch", 2, "replacement", "worker", Instant.now());
        try (var restarted = router()) {
            assertEquals(100, restarted.publishFences(ClusterTargetRouter.allSlots(), newer, guard).orElseThrow());
            restarted.apply(batch, checkpoint(100, 2), newer, guard);
            assertEquals(100, restarted.checkpoint().orElseThrow().appliedOffset());
        }
        for (int i = 0; i < nodes.size(); i++)
            assertEquals("1", value(call(nodes.get(i), "GET", key(masters.get(i).slotStart(), "counter"))));
    }

    private ClusterTargetRouter router() throws Exception {
        return new ClusterTargetRouter(profile, resolver, taskId, "fixture", Duration.ofSeconds(2));
    }
    private TargetCheckpoint checkpoint(long offset, long generation) {
        return new TargetCheckpoint("epoch", generation, "repl", offset, 0, Instant.now());
    }
    private String key(int slot, String suffix) {
        return "batch-recovery:{" + ClusterSlotKeyspace.tag(slot) + "}:" + taskId + ":" + suffix;
    }
    private CommandPlan.PlannedCommand planned(int slot, String... args) {
        return new CommandPlan.PlannedCommand(slot,
                Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8)).toList());
    }
    private RespValue call(RedisEndpoint endpoint, String... args) throws Exception {
        try (var socket = new Socket(endpoint.host(), endpoint.port())) {
            socket.setSoTimeout(3000);
            var codec = new RespCodec(socket.getInputStream(), socket.getOutputStream());
            codec.writeCommand(args);
            return codec.read();
        }
    }
    private String value(RespValue response) {
        return new String(((RespValue.Bulk) response).value(), StandardCharsets.UTF_8);
    }
}
