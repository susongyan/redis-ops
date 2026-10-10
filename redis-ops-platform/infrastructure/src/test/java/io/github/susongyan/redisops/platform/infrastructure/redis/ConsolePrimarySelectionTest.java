package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsolePrimarySelectionTest {
    RedisClusterNode node(String id, boolean connected, NodeFlag... flags) {
        var node = new RedisClusterNode();
        node.setNodeId(id);
        node.setConnected(connected);
        node.setFlags(new HashSet<>(List.of(flags)));
        return node;
    }
    @Test
    void selectsOneHealthyMasterNotReplicaOrFailedNode() {
        var master = node("b", true, NodeFlag.MASTER);
        assertSame(master, LettuceRedisOperationAdapter.selectPrimary(List.of(
                node("a", true, NodeFlag.REPLICA), node("0", true, NodeFlag.MASTER, NodeFlag.FAIL),
                node("1", false, NodeFlag.MASTER), master, node("c", true, NodeFlag.MASTER))));
    }
    @Test
    void noHealthyMasterFailsClosed() {
        assertThrows(IllegalStateException.class, () -> LettuceRedisOperationAdapter.selectPrimary(List.of(
                node("a", true, NodeFlag.REPLICA), node("b", true, NodeFlag.MASTER, NodeFlag.EVENTUAL_FAIL))));
    }
}
