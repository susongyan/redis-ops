package io.github.susongyan.redisops.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncRuntime;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncExecutionPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnabledIfEnvironmentVariable(named = "REDIS_SYNC_CLUSTER_IT", matches = "true")
class ClusterSyncTaskRunnerIntegrationTest {
    private static final List<Integer> SOURCE_PORTS = List.of(7101, 7102, 7103);
    private static final List<Integer> TARGET_PORTS = List.of(7201, 7202, 7203);

    @TempDir
    Path dataDirectory;

    @Test
    void copiesAllSourceMastersAndRecoversFromClusterCheckpoints() throws Exception {
        flushMasters(SOURCE_PORTS);
        flushMasters(TARGET_PORTS);
        RedisClusterClient sourceClient = clusterClient(SOURCE_PORTS);
        RedisClusterClient targetClient = clusterClient(TARGET_PORTS);
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            var sourceCommands = source.sync();
            var targetCommands = target.sync();
            sourceCommands.set("{orders}:one", "order-1");
            sourceCommands.hset("{accounts}:profile", "name", "redis-ops");
            sourceCommands.rpush("{events}:queue", "created", "paid");
            sourceCommands.set("{binary}:value", "payload");
            for (int i = 0; i < 300; i++)
                sourceCommands.set("cluster-full:" + i, "value-" + i);

            WorkerSyncTask task = task(193, 1, 2, "epoch-cluster-it");
            WorkerRedisConnectionProfilePort profiles = clusterProfiles();
            String keyRing = keyRing();
            ClusterSyncTaskRunner runner = runner(task, false, profiles, keyRing);
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();
                await(() -> "order-1".equals(targetCommands.get("{orders}:one"))
                        && "redis-ops".equals(targetCommands.hget("{accounts}:profile", "name"))
                        && List.of("created", "paid").equals(targetCommands.lrange("{events}:queue", 0, -1))
                        && "value-299".equals(targetCommands.get("cluster-full:299")), runner);

                sourceCommands.incr("{orders}:counter");
                sourceCommands.lpush("{events}:queue", "received");
                sourceCommands.set("{accounts}:incremental", "copied");
                await(() -> "1".equals(targetCommands.get("{orders}:counter"))
                        && List.of("received", "created", "paid")
                                .equals(targetCommands.lrange("{events}:queue", 0, -1))
                        && "copied".equals(targetCommands.get("{accounts}:incremental")), runner);

                runner.pause();
                sourceCommands.incr("{orders}:paused");
                if (v3())
                    sourceCommands.eval("redis.call('INCR',KEYS[1]); redis.call('INCR',KEYS[2]); return 1",
                            io.lettuce.core.ScriptOutputType.INTEGER, new String[]{"{orders}:tx-a", "{orders}:tx-b"});
                Thread.sleep(500);
                assertEquals(null, targetCommands.get("{orders}:paused"));
                if (v3())
                    assertEquals(null, targetCommands.get("{orders}:tx-a"));
                runner.resume();
                await(() -> "1".equals(targetCommands.get("{orders}:paused")), runner);
                if (v3())
                    await(() -> "1".equals(targetCommands.get("{orders}:tx-a"))
                            && "1".equals(targetCommands.get("{orders}:tx-b")), runner);
                // Business values may be visible before checkpoint confirmation. This is the
                // confirmed-boundary recovery test; interrupted pending is tested separately.
                runner.pause();
            } finally {
                runner.close();
            }

            ClusterSyncTaskRunner recovered = runner(task, true, profiles, keyRing);
            try {
                recovered.prepare();
                recovered.leaseAcquired(runtime(task.id(), 2));
                recovered.resume();
                Thread.sleep(500);
                assertEquals("1", targetCommands.get("{orders}:counter"),
                        "checkpoint recovery must not replay a committed INCR");
                if (v3()) {
                    assertEquals("1", targetCommands.get("{orders}:tx-a"));
                    assertEquals("1", targetCommands.get("{orders}:tx-b"));
                }
                sourceCommands.incr("{orders}:counter");
                sourceCommands.lpush("{events}:queue", "recovered");
                await(() -> "2".equals(targetCommands.get("{orders}:counter"))
                        && "recovered".equals(targetCommands.lindex("{events}:queue", 0)), recovered);
            } finally {
                recovered.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    @Test
    void convertsStandaloneToClusterAndSplitsSafeMultiKeyWrites() throws Exception {
        flushStandalone(7301);
        flushMasters(TARGET_PORTS);
        RedisClient sourceClient = RedisClient.create("redis://127.0.0.1:7301");
        RedisClusterClient targetClient = clusterClient(TARGET_PORTS);
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            source.sync().mset(Map.of("{north}:one", "n1", "{south}:one", "s1"));
            source.sync().set("{west}:full", "w1");
            WorkerSyncTask task = task(194, 3, 2, "epoch-standalone-cluster");
            WorkerRedisConnectionProfilePort profiles = clusterId -> {
                if (clusterId == 3)
                    return new WorkerRedisConnectionProfile(clusterId, WorkerClusterMode.STANDALONE,
                            List.of("127.0.0.1:7301"), null, null, "NONE", null);
                return new WorkerRedisConnectionProfile(clusterId, WorkerClusterMode.CLUSTER, endpoints(TARGET_PORTS),
                        null, null, "NONE", null);
            };
            ClusterSyncTaskRunner runner = runner(task, false, profiles, keyRing());
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();
                await(() -> "n1".equals(target.sync().get("{north}:one"))
                        && "s1".equals(target.sync().get("{south}:one"))
                        && "w1".equals(target.sync().get("{west}:full")), runner);

                source.sync().mset(Map.of("{north}:two", "n2", "{south}:two", "s2"));
                await(() -> "n2".equals(target.sync().get("{north}:two"))
                        && "s2".equals(target.sync().get("{south}:two")), runner);
            } finally {
                runner.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    @Test
    void mergesClusterMasterChannelsIntoStandalone() throws Exception {
        flushMasters(SOURCE_PORTS);
        flushStandalone(7302);
        RedisClusterClient sourceClient = clusterClient(SOURCE_PORTS);
        RedisClient targetClient = RedisClient.create("redis://127.0.0.1:7302");
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            source.sync().set("{alpha}:full", "a1");
            source.sync().hset("{beta}:hash", "field", "b1");
            source.sync().rpush("{gamma}:list", "g1", "g2");
            WorkerSyncTask task = task(195, 1, 4, "epoch-cluster-standalone");
            WorkerRedisConnectionProfilePort profiles = clusterId -> {
                if (clusterId == 1)
                    return new WorkerRedisConnectionProfile(clusterId, WorkerClusterMode.CLUSTER,
                            endpoints(SOURCE_PORTS),
                            null, null, "NONE", null);
                return new WorkerRedisConnectionProfile(clusterId, WorkerClusterMode.STANDALONE,
                        List.of("127.0.0.1:7302"), null, null, "NONE", null);
            };
            ClusterSyncTaskRunner runner = runner(task, false, profiles, keyRing());
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();
                await(() -> "a1".equals(target.sync().get("{alpha}:full"))
                        && "b1".equals(target.sync().hget("{beta}:hash", "field"))
                        && List.of("g1", "g2").equals(target.sync().lrange("{gamma}:list", 0, -1)), runner);

                source.sync().incr("{alpha}:counter");
                source.sync().hset("{beta}:hash", "next", "b2");
                await(() -> "1".equals(target.sync().get("{alpha}:counter"))
                        && "b2".equals(target.sync().hget("{beta}:hash", "next")), runner);
            } finally {
                runner.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    @Test
    void splitsOversizeKeysThroughSlotSafeTempKeys() throws Exception {
        flushMasters(SOURCE_PORTS);
        flushMasters(TARGET_PORTS);
        RedisClusterClient sourceClient = clusterClient(SOURCE_PORTS);
        RedisClusterClient targetClient = clusterClient(TARGET_PORTS);
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            var sourceCommands = source.sync();
            var targetCommands = target.sync();
            sourceCommands.set("small:str", "ok");
            sourceCommands.set("big:str", "x".repeat(8192));
            // One key per temp-naming case (§6.1): hash-tagged, untagged, untagged with '}'.
            String member = "m".repeat(100);
            for (int i = 0; i < 60; i++)
                sourceCommands.rpush("{bigtag}:list", member + ":" + i);
            sourceCommands.expire("{bigtag}:list", 600);
            for (int i = 0; i < 30; i++)
                sourceCommands.hset("big:plain:hash", "field:" + i, "v".repeat(200));
            for (int i = 0; i < 60; i++)
                sourceCommands.rpush("weird}big:list", member + ":" + i);
            // Incompressible value over the hard cap: skipped with an alert, never applied.
            var random = new java.util.Random(42);
            var huge = new StringBuilder(200_000);
            String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            for (int i = 0; i < 200_000; i++)
                huge.append(alphabet.charAt(random.nextInt(alphabet.length())));
            sourceCommands.set("huge:str", huge.toString());

            var task = task(197, 1, 2, "epoch-cluster-split", "{\"allowSafeSplit\":true}");
            WorkerRedisConnectionProfilePort profiles = clusterProfiles();
            String keyRing = keyRing();
            WorkerSyncExecutionPort sync = mock(WorkerSyncExecutionPort.class);
            // 4KB restore threshold, 8-command batches, 64KB hard cap.
            var split = new SplitKeySettings(4096, 4096, 8, 65536, false);
            ClusterSyncTaskRunner runner = runner(task, false, profiles, keyRing, sync, split);
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();

                await(() -> "ok".equals(targetCommands.get("small:str"))
                        && sourceCommands.get("big:str").equals(targetCommands.get("big:str"))
                        && sourceCommands.lrange("{bigtag}:list", 0, -1)
                                .equals(targetCommands.lrange("{bigtag}:list", 0, -1))
                        && sourceCommands.hgetall("big:plain:hash")
                                .equals(targetCommands.hgetall("big:plain:hash"))
                        && sourceCommands.lrange("weird}big:list", 0, -1)
                                .equals(targetCommands.lrange("weird}big:list", 0, -1)),
                        runner);
                assertTrue(targetCommands.pttl("{bigtag}:list") > 0, "TTL must survive the RENAME finalize");
                assertNull(targetCommands.get("huge:str"), "over-hard-cap key must be skipped");
                for (int port : TARGET_PORTS)
                    assertTrue(nodeKeys(port, "*ropstmppart*").isEmpty(),
                            "no split temp key residue on 127.0.0.1:" + port);
                verify(sync).appendTaskEvent(eq(task.id()), eq("sync:split"),
                        contains("SPLIT_KEY_SKIPPED"));

                // Splitting hands control back cleanly: incremental replication keeps working.
                sourceCommands.incr("{bigtag}:counter");
                await(() -> "1".equals(targetCommands.get("{bigtag}:counter")), runner);
            } finally {
                runner.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    private ClusterSyncTaskRunner runner(WorkerSyncTask task, boolean recovery,
            WorkerRedisConnectionProfilePort profiles, String keyRing) {
        return runner(task, recovery, profiles, keyRing, mock(WorkerSyncExecutionPort.class),
                new SplitKeySettings(0, 64 * 1024, 128, 0, false));
    }

    private ClusterSyncTaskRunner runner(WorkerSyncTask task, boolean recovery,
            WorkerRedisConnectionProfilePort profiles, String keyRing, WorkerSyncExecutionPort sync,
            SplitKeySettings split) {
        return new ClusterSyncTaskRunner(task, recovery, profiles, sync,
                mock(SyncRunnerStateReporter.class), new SpoolKeyProvider(keyRing),
                new RedisDataEndpointResolver(5000), new ObjectMapper(),
                dataDirectory, 1024 * 1024, Duration.ofSeconds(5), Duration.ofSeconds(10), 4, 32, 8,
                4 * 1024 * 1024L, 64 * 1024 * 1024L, Duration.ofSeconds(2), Duration.ofMillis(200),
                split, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static WorkerRedisConnectionProfilePort clusterProfiles() {
        return clusterId -> new WorkerRedisConnectionProfile(clusterId, WorkerClusterMode.CLUSTER,
                endpoints(clusterId == 1 ? SOURCE_PORTS : TARGET_PORTS), null, null, "NONE", null);
    }

    private static WorkerSyncTask task(long id, long sourceClusterId, long targetClusterId, String epoch) {
        return task(id, sourceClusterId, targetClusterId, epoch,
                v3() ? "{\"policyVersion\":\"v3\",\"allowSafeSplit\":true}" : "{}");
    }

    private static WorkerSyncTask task(long id, long sourceClusterId, long targetClusterId, String epoch,
            String policy) {
        Instant now = Instant.now();
        return new WorkerSyncTask(id, "SYNC-CLUSTER-IT-" + id, null, sourceClusterId, targetClusterId,
                "MIGRATION", "FULL_AND_INCREMENTAL", SyncContractStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", policy, "FLUSH",
                50_000,
                100_000_000, 64 * 1024 * 1024, 4, 8, "START", true,
                "cluster integration", null, epoch, null, null, 0, now, now, null);
    }

    private static WorkerSyncRuntime runtime(long taskId, long generation) {
        return new WorkerSyncRuntime(taskId, "runtime-" + generation, "worker", Instant.now().plusSeconds(30),
                generation, "CLAIMED", Instant.now(), 0, null, null, 0,
                null, null, Instant.now(), Instant.now());
    }

    private static boolean v3() {
        return "v3".equals(System.getenv("SYNC_IT_POLICY_VERSION"));
    }

    private static RedisClusterClient clusterClient(List<Integer> ports) {
        return RedisClusterClient.create(ports.stream()
                .map(port -> RedisURI.create("redis://127.0.0.1:" + port)).toList());
    }

    private static List<String> endpoints(List<Integer> ports) {
        return ports.stream().map(port -> "127.0.0.1:" + port).toList();
    }

    private static void flushMasters(List<Integer> ports) {
        for (int port : ports) {
            RedisClient client = RedisClient.create("redis://127.0.0.1:" + port);
            try (var connection = client.connect()) {
                connection.sync().flushall();
            } finally {
                client.shutdown();
            }
        }
    }

    private static void flushStandalone(int port) {
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + port);
        try (var connection = client.connect()) {
            connection.sync().flushall();
        } finally {
            client.shutdown();
        }
    }

    /** KEYS on one specific node — the cluster client cannot target a single master. */
    private static List<String> nodeKeys(int port, String pattern) {
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + port);
        try (var connection = client.connect()) {
            return connection.sync().keys(pattern);
        } finally {
            client.shutdown();
        }
    }

    private static String keyRing() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 17);
        return "v1:" + Base64.getEncoder().encodeToString(key);
    }

    private static void await(Check check, ClusterSyncTaskRunner runner) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!check.done()) {
            if ("FAILED".equals(runner.phase()) || "BLOCKED".equals(runner.phase()))
                fail("Cluster runner entered " + runner.phase() + ": " + runner.lastFailure());
            if (System.nanoTime() >= deadline)
                fail("timed out waiting for Cluster replication; phase=" + runner.phase()
                        + ", spoolBytes=" + runner.spoolBytes());
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean done();
    }
}
