package io.github.redisops.domain.location;

import java.time.Instant;

public record Idc(Long id, String code, String name, Long regionId, String regionCode, String regionName,
        String networkDomain, ResourceStatus status, String description, long version,
        Instant createdAt, Instant updatedAt) {
}
