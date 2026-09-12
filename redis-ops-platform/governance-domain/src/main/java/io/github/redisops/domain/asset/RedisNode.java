package io.github.redisops.domain.asset;

public record RedisNode(Long id, long clusterId, String host, int port, String nodeId,
        String role, String masterNodeId, String slotRanges,
        Long memoryBytes, String status) {
}
