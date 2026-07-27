package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.RespProtocolException;
import io.github.redisops.sync.protocol.RespValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisDataEndpointResolverTest {

    @Test
    void usesFirstSeedForStandalone() throws Exception {
        RedisDataEndpointResolver resolver = new RedisDataEndpointResolver(1000);
        try (RedisConnectionProfile profile = new RedisConnectionProfile(1, ClusterMode.STANDALONE,
                List.of("127.0.0.1:6380", "127.0.0.1:6381"), null, null, "NONE", null)) {
            assertEquals(new RedisEndpoint("127.0.0.1", 6380), resolver.resolvePrimary(profile));
        }
    }

    @Test
    void parsesSentinelMasterResponse() {
        RespValue response = new RespValue.Array(List.of(
                bulk("127.0.0.1"), bulk("6381")));
        assertEquals(new RedisEndpoint("127.0.0.1", 6381),
                RedisDataEndpointResolver.parseMaster(response));
    }

    @Test
    void rejectsMissingOrInvalidSentinelMaster() {
        assertThrows(RespProtocolException.class,
                () -> RedisDataEndpointResolver.parseMaster(RespValue.NullValue.INSTANCE));
        assertThrows(RespProtocolException.class, () -> RedisDataEndpointResolver.parseMaster(
                new RespValue.Array(List.of(bulk("127.0.0.1"), bulk("invalid")))));
    }

    @Test
    void parsesAndValidatesClusterSlotCoverage() {
        RespValue response = new RespValue.Array(List.of(
                slotRange(0, 5460, "10.0.0.1", 7001, "node-1"),
                slotRange(5461, 10922, "10.0.0.2", 7002, "node-2"),
                slotRange(10923, 16383, "10.0.0.3", 7003, "node-3")));
        var masters = RedisDataEndpointResolver.parseClusterSlots(response, "seed");
        assertEquals(3, masters.size());
        assertEquals(new RedisEndpoint("10.0.0.2", 7002), masters.get(1).endpoint());
        assertEquals(10922, masters.get(1).slotEnd());
    }

    @Test
    void usesSeedHostWhenClusterDoesNotAnnounceAHostAndRejectsGaps() {
        RespValue response = new RespValue.Array(List.of(slotRange(0, 16383, "", 7001, "node-1")));
        assertEquals(new RedisEndpoint("seed", 7001),
                RedisDataEndpointResolver.parseClusterSlots(response, "seed").get(0).endpoint());
        RespValue gap = new RespValue.Array(List.of(slotRange(1, 16383, "host", 7001, "node-1")));
        assertThrows(RespProtocolException.class,
                () -> RedisDataEndpointResolver.parseClusterSlots(gap, "seed"));
    }

    private static RespValue.Bulk bulk(String value) {
        return new RespValue.Bulk(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static RespValue.Array slotRange(long start, long end, String host, long port, String nodeId) {
        return new RespValue.Array(List.of(new RespValue.IntegerValue(start), new RespValue.IntegerValue(end),
                new RespValue.Array(List.of(bulk(host), new RespValue.IntegerValue(port), bulk(nodeId)))));
    }
}
