package io.github.susongyan.redisops.platform.infrastructure.distribution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, isolated fixtures only. Does not modify Redis. */
@EnabledIfEnvironmentVariable(named = "REPLICA_SCAN_SMOKE", matches = "true")
class ReplicaScanSmokeTest {
    BoundedDistributionRedis redis(ClusterMode mode) {
        int port = mode == ClusterMode.CLUSTER ? 17691 : mode == ClusterMode.SENTINEL ? 17681 : 17679;
        return new BoundedDistributionRedis(id -> new RedisConnectionProfile(id, mode, List.of("127.0.0.1:" + port),
                mode == ClusterMode.SENTINEL ? "scan-primary" : null, null, "NONE", null));
    }
    @Test
    void standaloneAndSentinelScanReplica() {
        for (var mode : List.of(ClusterMode.STANDALONE, ClusterMode.SENTINEL)) {
            var redis = redis(mode);
            var topology = redis.topology(1, 0);
            assertEquals(1, topology.shards().size());
            var shard = topology.shards().get(0);
            assertEquals("replica", shard.role());
            assertEquals("127.0.0.1:17680", shard.endpoint());
            assertTrue(redis.count(1, 0, topology) > 0);
            assertFalse(redis.scan(1, 0, shard, "0", 200).keys().isEmpty());
            var metadata = redis.metadata(1, 0, shard, "scan-test:key".getBytes(StandardCharsets.UTF_8));
            assertEquals("string", metadata.type());
            assertEquals(-1, metadata.ttlSeconds());
            assertTrue(metadata.size() > 0);
        }
    }
    @Test
    void clusterReadsAllThreeReplicasWithoutRedirectingKeyReads() {
        var redis = redis(ClusterMode.CLUSTER);
        var topology = redis.topology(1, 0);
        assertEquals(3, topology.shards().size());
        assertTrue(topology.shards().stream().allMatch(s -> s.role().equals("replica")));
        assertEquals(3, topology.shards().stream().map(s -> s.endpoint()).distinct().count());
        boolean found = false;
        for (var shard : topology.shards()) {
            String cursor = "0";
            do {
                var page = redis.scan(1, 0, shard, cursor, 200);
                cursor = page.cursor();
                for (byte[] key : page.keys()) {
                    var metadata = redis.metadata(1, 0, shard, key);
                    assertNotEquals("none", metadata.type());
                    assertTrue(metadata.size() > 0);
                    found = true;
                }
            } while (!cursor.equals("0"));
        }
        assertTrue(found);
    }
}
