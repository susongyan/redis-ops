package io.github.redisops.platform.domain.asset;

public record ApplicationBinding(long applicationId, long clusterId, String clientType,
        String clientVersion, String poolConfig) {
}
