package io.github.susongyan.redisops.platform.domain.asset;

public record ApplicationBinding(long applicationId, long clusterId, String clientType,
        String clientVersion, String poolConfig) {
}
