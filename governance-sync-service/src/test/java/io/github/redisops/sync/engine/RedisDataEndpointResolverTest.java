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

    private static RespValue.Bulk bulk(String value) {
        return new RespValue.Bulk(value.getBytes(StandardCharsets.US_ASCII));
    }
}
