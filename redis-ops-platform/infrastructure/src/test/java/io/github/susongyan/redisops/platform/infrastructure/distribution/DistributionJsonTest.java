package io.github.susongyan.redisops.platform.infrastructure.distribution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DistributionJsonTest {
    @Test
    void checkpointRetainsReplicaIdentityAndReadsLegacyPrimaryNodes() {
        var json = new DistributionJson(new ObjectMapper());
        var shard = new io.github.susongyan.redisops.platform.domain.distribution.DistributionScanPort.Shard(
                "primary", "replica:6379", "replica", "a".repeat(40), "replica-node");
        var state = new io.github.susongyan.redisops.platform.domain.distribution.DistributionCheckpoint("a".repeat(64),
                java.util.List
                        .of(new io.github.susongyan.redisops.platform.domain.distribution.DistributionCheckpoint.Cursor(
                                shard, "42", false)),
                0, 7, 20, java.util.List.of(), 200, 0);
        assertEquals(state, json.decode(json.encode(state, 2097152), state.getClass()));
        var old = json.decode("{\"id\":\"primary\",\"endpoint\":\"primary:6379\"}", shard.getClass());
        assertEquals("master", old.role());
        assertNull(old.runId());
    }
    @Test
    void stopsEncodingBeforeUnboundedBufferGrowth() {
        var json = new DistributionJson(new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> json.encode(java.util.List.of("x".repeat(1000)), 32));
        assertEquals("[1,2]", json.encode(java.util.List.of(1, 2), 32));
    }
}
