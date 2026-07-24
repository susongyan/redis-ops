package io.github.redisops.domain.asset;

import io.github.redisops.common.BusinessException;
import java.time.Instant;

public record RedisCluster(
        Long id, String name, String environment, String businessLine, String owner,
        String opsOwner, String serviceLevel, ClusterMode mode, String redisVersion,
        String endpoint, Long idcId,
        ClusterStatus status, long version, Instant createdAt, Instant updatedAt) {

    public RedisCluster {
        requireText(name, "name");
        requireText(environment, "environment");
        requireText(owner, "owner");
        requireText(endpoint, "endpoint");
        if (mode == null)
            throw new BusinessException("INVALID_ARGUMENT", "mode is required");
        if (status == null)
            status = ClusterStatus.ACTIVE;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", field + " is required");
    }
}
