package io.github.redisops.domain.asset;

public record ApplicationBinding(long applicationId, long clusterId, String clientType,
        String clientVersion, String poolConfig) {
}
