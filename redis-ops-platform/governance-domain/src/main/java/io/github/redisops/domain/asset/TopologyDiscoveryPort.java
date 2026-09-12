package io.github.redisops.domain.asset;

import java.util.List;

public interface TopologyDiscoveryPort {
    List<RedisNode> discover(RedisCluster cluster);
}
