package io.github.susongyan.redisops.platform.infrastructure.distribution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplicaScanSelectionTest {
    final Map<String, String> roles = new HashMap<>(Map.of("p:1", "master", "r:2", "slave"));
    final Map<String, String> runs = new HashMap<>();
    String nodes = "p p:1@1001 master - 0 0 1 connected 0-16383\nr r:2@1002 slave p 0 0 1 connected";
    boolean syncing, unavailable;
    Object read(String address, String... command) {
        return switch (String.join(" ", command)) {
            case "CLUSTER NODES" -> nodes;
            case "ROLE" -> "master".equals(roles.get(address))
                    ? List.of("master", 0L, List.of(List.of("r", 2L, 0L)))
                    : List.of("slave", "p", 1L, "connected", 0L);
            case "INFO replication" -> {
                if (unavailable)
                    throw new IllegalStateException("unavailable");
                yield "master_link_status:up\r\nmaster_sync_in_progress:" + (syncing ? "1" : "0") + "\r\n";
            }
            case "INFO server" -> "run_id:" + runs.getOrDefault(address, "a".repeat(40)) + "\r\n";
            default -> throw new IllegalArgumentException();
        };
    }
    @Test
    void clusterPrefersOneReplicaAndFallbacksWithoutConfiguration() throws Exception {
        var replica = ReplicaScanSelection.select("p:1", true, this::read);
        assertEquals(1, replica.shards().size());
        assertEquals("replica", replica.shards().get(0).role());
        assertEquals("r:2", replica.shards().get(0).endpoint());
        unavailable = true;
        var primary = ReplicaScanSelection.select("p:1", true, this::read);
        assertEquals("master", primary.shards().get(0).role());
        assertEquals("p:1", primary.shards().get(0).endpoint());
        assertNotEquals(replica.fingerprint(), primary.fingerprint());
    }
    @Test
    void standalonePrefersReplicaButDoesNotUseSyncingReplica() throws Exception {
        assertEquals("replica", ReplicaScanSelection.select("p:1", false, this::read).shards().get(0).role());
        syncing = true;
        assertEquals("master", ReplicaScanSelection.select("p:1", false, this::read).shards().get(0).role());
    }
    @Test
    void failedReplicaDoesNotBlockHealthyMasterFallback() throws Exception {
        nodes = nodes.replace("slave p", "slave,fail p");
        assertEquals("master", ReplicaScanSelection.select("p:1", true, this::read).shards().get(0).role());
    }
    @Test
    void restartedReplicaChangesCursorIdentity() throws Exception {
        var before = ReplicaScanSelection.select("p:1", true, this::read);
        runs.put("r:2", "b".repeat(40));
        var after = ReplicaScanSelection.select("p:1", true, this::read);
        assertNotEquals(before.fingerprint(), after.fingerprint());
        assertNotEquals(before.checkpointId(before.shards().get(0)), after.checkpointId(after.shards().get(0)));
        assertTrue(after.checkpointId(after.shards().get(0)).length() <= 128);
    }
    @Test
    void missingOrMigratingSlotsCannotBeReportedAsFullTopology() {
        nodes = nodes.replace("0-16383", "0-100");
        assertThrows(Exception.class, () -> ReplicaScanSelection.select("p:1", true, this::read));
        nodes = nodes.replace("0-100", "0-16383 [10->-other]");
        assertThrows(Exception.class, () -> ReplicaScanSelection.select("p:1", true, this::read));
    }
    @Test
    void everyPrimaryGetsExactlyOneTarget() throws Exception {
        roles.put("p2:3", "master");
        nodes = nodes.replace("0-16383", "0-8191") + "\np2 p2:3@1003 master - 0 0 2 connected 8192-16383";
        var topology = ReplicaScanSelection.select("p:1", true, this::read);
        assertEquals(List.of("replica", "master"), topology.shards().stream().map(s -> s.role()).toList());
        assertEquals(2, topology.shards().stream().map(s -> s.id()).distinct().count());
    }
}
