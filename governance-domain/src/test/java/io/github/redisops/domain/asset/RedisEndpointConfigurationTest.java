package io.github.redisops.domain.asset;

import io.github.redisops.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisEndpointConfigurationTest {
    @Test
    void parsesClusterSeedsAndRemovesDuplicates() {
        var result = RedisEndpointConfiguration.parse(ClusterMode.CLUSTER,
                "redis-a:6379, redis-b:6380,redis-a:6379");
        assertEquals(List.of("redis-a:6379", "redis-b:6380"), result.seedEndpoints());
    }

    @Test
    void parsesSentinelMasterName() {
        var result = RedisEndpointConfiguration.parse(ClusterMode.SENTINEL,
                "orders-master@sentinel-a:26379,sentinel-b:26379");
        assertEquals("orders-master", result.sentinelMasterName());
        assertEquals(2, result.seedEndpoints().size());
    }

    @Test
    void rejectsInvalidPortAndModeSyntax() {
        assertThrows(BusinessException.class,
                () -> RedisEndpointConfiguration.parse(ClusterMode.STANDALONE, "redis:70000"));
        assertThrows(BusinessException.class,
                () -> RedisEndpointConfiguration.parse(ClusterMode.CLUSTER, "master@redis:6379"));
        assertThrows(BusinessException.class,
                () -> RedisEndpointConfiguration.parse(ClusterMode.SENTINEL, "sentinel:26379"));
    }

    @Test
    void acceptsBracketedIpv6() {
        assertEquals(List.of("[2001:db8::1]:6379"),
                RedisEndpointConfiguration.parse(ClusterMode.STANDALONE, "[2001:db8::1]:6379")
                        .seedEndpoints());
    }
}
