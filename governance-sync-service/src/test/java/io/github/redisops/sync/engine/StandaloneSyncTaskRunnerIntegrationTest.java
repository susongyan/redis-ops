package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.sync.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XReadArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "REDIS_SYNC_IT", matches = "true")
class StandaloneSyncTaskRunnerIntegrationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void copiesFullRdbAndContinuesWithNonIdempotentCommands() throws Exception {
        RedisClient sourceClient = RedisClient.create(RedisURI.create("redis://127.0.0.1:6390"));
        RedisClient targetClient = RedisClient.create(RedisURI.create("redis://127.0.0.1:6391"));
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            source.sync().flushall();
            target.sync().flushall();
            source.sync().set("name", "redis-ops");
            source.sync().hset("hash", "field", "value");
            source.sync().rpush("items", "a", "b");
            source.sync().sadd("set", "x", "y");
            source.sync().zadd("scores", 1.5, "member");
            source.sync().psetex("expires", 60_000, "ttl");
            source.sync().xadd("events", Map.of("type", "created"));
            source.sync().xgroupCreate(XReadArgs.StreamOffset.from("events", "0-0"), "workers");
            for (int i = 0; i < 500; i++)
                source.sync().set("parallel:" + i, "value-" + i);
            source.sync().select(1);
            source.sync().set("other-db-full", "must-not-copy");
            source.sync().select(0);

            SyncTask task = task();
            RedisConnectionProfileProvider profiles = clusterId -> new RedisConnectionProfile(clusterId,
                    ClusterMode.STANDALONE, List.of("127.0.0.1:" + (clusterId == 1 ? 6390 : 6391)),
                    null, null, "NONE", null);
            byte[] masterKey = new byte[32];
            java.util.Arrays.fill(masterKey, (byte) 9);
            String keyRing = "v1:" + Base64.getEncoder().encodeToString(masterKey);
            var runner = runner(task, false, profiles, keyRing);
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();

                await(() -> "redis-ops".equals(target.sync().get("name"))
                        && "value".equals(target.sync().hget("hash", "field"))
                        && List.of("a", "b").equals(target.sync().lrange("items", 0, -1))
                        && "value-499".equals(target.sync().get("parallel:499")), runner);
                assertEquals("value", target.sync().hget("hash", "field"));
                assertEquals(List.of("a", "b"), target.sync().lrange("items", 0, -1));
                assertTrue(target.sync().pttl("expires") > 0);
                assertEquals(1, target.sync().xlen("events"));
                assertFalse(target.sync().xinfoGroups("events").isEmpty());
                assertNull(target.sync().get("other-db-full"));
                assertEquals("value-0", target.sync().get("parallel:0"));
                assertEquals("value-249", target.sync().get("parallel:249"));
                assertEquals("value-499", target.sync().get("parallel:499"));

                source.sync().incr("counter");
                source.sync().lpush("items", "c");
                await(() -> "1".equals(target.sync().get("counter")), runner);
                await(() -> List.of("c", "a", "b").equals(target.sync().lrange("items", 0, -1)), runner);
                source.sync().select(1);
                source.sync().set("other-db-incremental", "must-not-copy");
                source.sync().select(0);
                source.sync().set("selected-db-incremental", "copied");
                await(() -> "copied".equals(target.sync().get("selected-db-incremental")), runner);
                assertNull(target.sync().get("other-db-incremental"));

                runner.pause();
                source.sync().incr("paused-counter");
                Thread.sleep(500);
                assertNull(target.sync().get("paused-counter"));
                runner.resume();
                await(() -> "1".equals(target.sync().get("paused-counter")), runner);
            } finally {
                runner.close();
            }

            var recovered = runner(task, true, profiles, keyRing);
            try {
                recovered.prepare();
                recovered.leaseAcquired(runtime(task.id(), 2));
                recovered.resume();
                Thread.sleep(500);
                assertEquals("1", target.sync().get("counter"),
                        "checkpoint recovery must not replay a committed INCR");
                source.sync().incr("counter");
                await(() -> "2".equals(target.sync().get("counter")), recovered);

                try (RedisConnectionProfile targetProfile = profiles.get(2);
                        TargetCommandSession targetSession = new TargetCommandSession(
                                targetProfile, 0, task.id(), Duration.ofSeconds(5))) {
                    LeaseGuard validLease = new LeaseGuard(Duration.ZERO);
                    validLease.grant(Duration.ofSeconds(30));
                    TargetFence oldFence = new TargetFence(task.fullSyncEpoch(), 1,
                            "runtime-1", "worker", Instant.now());
                    assertThrows(TargetCommandSession.FencingException.class,
                            () -> targetSession.restoreBatch(List.of(
                                    new TargetCommandSession.RestoreRequest(
                                            "stale-full-write".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                            0, new byte[]{1, 2, 3})),
                                    oldFence, 0, validLease));
                    assertNull(target.sync().get("stale-full-write"));

                    TargetCheckpoint current = targetSession.checkpoint().orElseThrow();
                    TargetFence currentFence = new TargetFence(task.fullSyncEpoch(), 2,
                            "runtime-2", "worker", Instant.now());
                    TargetCheckpoint nonRegressed = targetSession.apply(List.of(),
                            new TargetCheckpoint(task.fullSyncEpoch(), 2, current.replicationId(),
                                    current.appliedOffset() - 1, current.sourceDatabase(), Instant.now()),
                            currentFence, validLease);
                    assertEquals(current.appliedOffset(), nonRegressed.appliedOffset());
                    assertEquals(current.appliedOffset(),
                            targetSession.checkpoint().orElseThrow().appliedOffset());
                }
            } finally {
                recovered.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_SYNC_ACL_IT", matches = "true")
    void copiesWithAclAuthentication() throws Exception {
        RedisURI sourceUri = RedisURI.builder().withHost("127.0.0.1").withPort(6392)
                .withAuthentication("sync", "sync-secret".toCharArray()).build();
        RedisURI targetUri = RedisURI.builder().withHost("127.0.0.1").withPort(6393)
                .withAuthentication("sync", "sync-secret".toCharArray()).build();
        RedisClient sourceClient = RedisClient.create(sourceUri);
        RedisClient targetClient = RedisClient.create(targetUri);
        try (var source = sourceClient.connect(); var target = targetClient.connect()) {
            source.sync().flushall();
            target.sync().flushall();
            source.sync().set("acl-key", "full");
            SyncTask task = task(92, "epoch-acl");
            RedisConnectionProfileProvider profiles = clusterId -> new RedisConnectionProfile(clusterId,
                    ClusterMode.STANDALONE, List.of("127.0.0.1:" + (clusterId == 1 ? 6392 : 6393)),
                    null, "sync", "ACL", "sync-secret".toCharArray());
            byte[] masterKey = new byte[32];
            String keyRing = "v1:" + Base64.getEncoder().encodeToString(masterKey);
            var runner = runner(task, false, profiles, keyRing);
            try {
                runner.prepare();
                runner.leaseAcquired(runtime(task.id(), 1));
                runner.start();
                await(() -> "full".equals(target.sync().get("acl-key")), runner);
                source.sync().incr("acl-counter");
                await(() -> "1".equals(target.sync().get("acl-counter")), runner);
            } finally {
                runner.close();
            }
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }

    private static void await(Check check, StandaloneSyncTaskRunner runner) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!check.done()) {
            if ("FAILED".equals(runner.phase()) || "BLOCKED".equals(runner.phase()))
                fail("runner entered " + runner.phase() + ": " + runner.lastFailure());
            if (System.nanoTime() >= deadline)
                fail("timed out waiting for replication; phase=" + runner.phase() + ", error="
                        + runner.lastFailure() + ", " + runner.diagnostics());
            Thread.sleep(50);
        }
    }

    private static SyncTask task() {
        return task(91, "epoch-it");
    }

    private static SyncTask task(long id, String epoch) {
        Instant now = Instant.now();
        return new SyncTask(id, "SYNC-IT-" + id, null, 1, 2, SyncPurpose.MIGRATION,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.STARTING, "NATIVE_JAVA", 0, 0,
                "[\"*\"]", "[]", 50_000, 100_000_000, 50 * 1024 * 1024, 4, 8, "START", true, "integration",
                null, epoch, null, null, 0, now, now, null);
    }

    private StandaloneSyncTaskRunner runner(SyncTask task, boolean recovery,
            RedisConnectionProfileProvider profiles, String keyRing) {
        return new StandaloneSyncTaskRunner(task, recovery, profiles, mock(SyncRepository.class),
                mock(SyncRunnerStateReporter.class), new SpoolKeyProvider(keyRing), new ObjectMapper(),
                dataDirectory, 1024 * 1024, Duration.ofSeconds(5), 4, 32, 8,
                4 * 1024 * 1024L, Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    private static SyncRuntime runtime(long taskId, long generation) {
        return new SyncRuntime(taskId, "runtime-" + generation, "worker", Instant.now().plusSeconds(30),
                generation, "CLAIMED", Instant.now(), 0, null, null, 0,
                null, null, Instant.now(), Instant.now());
    }

    @FunctionalInterface
    private interface Check {
        boolean done();
    }
}
