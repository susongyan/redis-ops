package io.github.redisops.domain.location;

import java.time.Instant;

public record Region(Long id,String code,String name,ResourceStatus status,String description,long version,
                     Instant createdAt,Instant updatedAt) { }
