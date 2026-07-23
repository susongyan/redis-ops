package io.github.redisops.domain.relation;
import java.time.Instant;
public record ClusterRelation(Long id,String name,RelationType relationType,long primaryClusterId,long standbyClusterId,
                              RelationStatus status,long desiredRpoSeconds,String description,long version,
                              Instant createdAt,Instant updatedAt) { }
