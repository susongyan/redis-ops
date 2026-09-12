package io.github.redisops.domain.asset;

import io.github.redisops.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedisClusterTest {
    @Test
    void requiresEndpoint() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new RedisCluster(null, "orders", "prod", null,
                        "owner", null, null, ClusterMode.CLUSTER, null, " ", null, null, 0, null, null));
        assertEquals("INVALID_ARGUMENT", error.code());
    }
    @Test
    void defaultsStatusToActive() {
        RedisCluster cluster = new RedisCluster(null, "orders", "prod", null, "owner", null, null,
                ClusterMode.CLUSTER, null, "redis:6379", null, null, 0, null, null);
        assertEquals(ClusterStatus.ACTIVE, cluster.status());
    }
}
