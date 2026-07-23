package io.github.redisops.domain.relation;
import java.util.*;
public interface ClusterRelationRepository {
    ClusterRelation save(ClusterRelation relation); Optional<ClusterRelation> find(long id); List<ClusterRelation> findAll();
    boolean update(ClusterRelation relation,long version); boolean delete(long id,long version);
}
