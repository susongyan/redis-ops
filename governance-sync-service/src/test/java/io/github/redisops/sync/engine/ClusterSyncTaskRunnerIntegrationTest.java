package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.sync.*;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

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

            SyncTask task = task(193, 1, 2, "epoch-cluster-it");
            RedisConnectionProfileProvider profiles = clusterProfiles();
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
                Thread.sleep(500);
                assertEquals(null, targetCommands.get("{orders}:paused"));
                runner.resume();
                await(() -> "1".equals(targetCommands.get("{orders}:paused")), runner);
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
            SyncTask task = task(194, 3, 2, "epoch-standalone-cluster");
            RedisConnectionProfileProvider profiles = clusterId -> {
                if (clusterId == 3)
                    return new RedisConnectionProfile(clusterId, ClusterMode.STANDALONE,
                            List.of("127.0.0.1:7301"), null, null, "NONE", null);
                return new RedisConnectionProfile(clusterId, ClusterMode.CLUSTER, endpoints(TARGET_PORTS),
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
            SyncTask task = task(195, 1, 4, "epoch-cluster-standalone");
            RedisConnectionProfileProvider profiles = clusterId -> {
                if (clusterId == 1)
                    return new RedisConnectionProfile(clusterId, ClusterMode.CLUSTER, endpoints(SOURCE_PORTS),
                            null, null, "NONE", null);
                return new RedisConnectionProfile(clusterId, ClusterMode.STANDALONE,
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

    private ClusterSyncTaskRunner runner(SyncTask task, boolean recovery,
            RedisConnectionProfileProvider profiles, String keyRing) {
        return new ClusterSyncTaskRunner(task, recovery, profiles, mock(SyncRepository.class),
                mock(SyncRunnerStateReporter.class), new SpoolKeyProvider(keyRing),
                new RedisDataEndpointResolver(5000), new ObjectMapper(),
                dataDirectory, 1024 * 1024, Duration.ofSeconds(5), 4, 32, 8,
                4 * 1024 * 1024L, Duration.ofSeconds(2), Duration.ofMillis(200));
    }

    private static RedisConnectionProfileProvider clusterProfiles() {
        return clusterId -> new RedisConnectionProfile(clusterId, ClusterMode.CLUSTER,
                endpoints(clusterId == 1 ? SOURCE_PORTS : TARGET_PORTS), null, null, "NONE", null);
    }

    private static SyncTask task(long id, long sourceClusterId, long targetClusterId, String epoch) {
        Instant now = Instant.now();
        return new SyncTask(id, "SYNC-CLUSTER-IT-" + id, null, sourceClusterId, targetClusterId,
                SyncPurpose.MIGRATION,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", 50_000, 100_000_000, 64 * 1024 * 1024, 4, 8, "START", true,
                "cluster integration", null, epoch, null, null, 0, now, now, null);
    }

    private static SyncRuntime runtime(long taskId, long generation) {
        return new SyncRuntime(taskId, "runtime-" + generation, "worker", Instant.now().plusSeconds(30),
                generation, "CLAIMED", Instant.now(), 0, null, null, 0,
                null, null, Instant.now(), Instant.now());
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
