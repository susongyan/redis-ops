package io.github.susongyan.redisops.platform.domain.asset;

import java.util.List;

public interface TopologyDiscoveryPort {
    List<RedisNode> discover(RedisCluster cluster);
}
