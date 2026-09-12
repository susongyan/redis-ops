package io.github.redisops.infrastructure.persistence;
import io.github.redisops.domain.relation.*;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class MyBatisClusterRelationRepository implements ClusterRelationRepository {
    private final RelationMapper mapper;
    public MyBatisClusterRelationRepository(RelationMapper mapper) {
        this.mapper = mapper;
    }
    public ClusterRelation save(ClusterRelation x) {
        var r = RelationMapper.Row.from(x);
        mapper.insert(r);
        return mapper.find(r.id);
    }
    public Optional<ClusterRelation> find(long id) {
        return Optional.ofNullable(mapper.find(id));
    }
    public List<ClusterRelation> findAll() {
        return mapper.findAll();
    }
    public boolean update(ClusterRelation x, long v) {
        return mapper.update(RelationMapper.Row.from(x), v) == 1;
    }
    public boolean delete(long id, long v) {
        return mapper.delete(id, v) == 1;
    }
}
