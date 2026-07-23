package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.RedisNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LettuceTopologyDiscoveryAdapterTest {
    @Test void parsesClusterNodesAndSlots() {
        String raw="aaa 10.0.0.1:6379@16379 master - 0 0 1 connected 0-8191\n"+
                "bbb 10.0.0.2:6379@16379 slave aaa 0 0 2 connected\n";
        List<RedisNode> nodes=LettuceTopologyDiscoveryAdapter.parseClusterNodes(9,raw);
        assertEquals(2,nodes.size());
        assertEquals("MASTER",nodes.get(0).role());
        assertEquals("[\"0-8191\"]",nodes.get(0).slotRanges());
        assertEquals("aaa",nodes.get(1).masterNodeId());
    }
    @Test void parsesSentinelEndpoint(){
        var endpoint=LettuceTopologyDiscoveryAdapter.SentinelEndpoint.parse("orders-master@127.0.0.1:26379,127.0.0.2:26379");
        assertEquals("orders-master",endpoint.masterName());assertEquals(2,endpoint.seeds().size());assertEquals(26379,endpoint.seeds().get(0).port());
    }
    @Test void parsesMultipleRedisSeeds(){var seeds=LettuceTopologyDiscoveryAdapter.HostPort.parseAll("10.0.0.1:6379, 10.0.0.2:6380");assertEquals(2,seeds.size());assertEquals("10.0.0.2",seeds.get(1).host());}

    @Test void classifiesAuthenticationFailureFromNestedLettuceCause() {
        RuntimeException lettuceFailure = new RuntimeException("Unable to connect to Redis",
                new RuntimeException("WRONGPASS invalid username-password pair"));
        RuntimeException failure = new BusinessException("TOPOLOGY_UNREACHABLE",
                "all Redis seed endpoints failed", lettuceFailure);

        BusinessException result = LettuceTopologyDiscoveryAdapter.connectionFailure(failure);

        assertEquals("REDIS_AUTHENTICATION_FAILED", result.code());
        assertFalse(result.getMessage().contains("wrong-password"));
    }
}
