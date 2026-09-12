package io.github.redisops.infrastructure.persistence;

import io.github.redisops.common.PageResult;
import io.github.redisops.domain.asset.*;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class MyBatisClusterRepository implements ClusterRepository {
    private final ClusterMapper mapper;
    public MyBatisClusterRepository(ClusterMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public RedisCluster save(RedisCluster cluster) {
        ClusterMapper.ClusterRow row = ClusterMapper.ClusterRow.from(cluster);
        mapper.insert(row);
        return mapper.findById(row.id);
    }
    @Override
    public Optional<RedisCluster> findById(long id) {
        return Optional.ofNullable(mapper.findById(id));
    }
    @Override
    public PageResult<RedisCluster> findAll(ClusterQuery q) {
        String status = q.status() == null ? null : q.status().name();
        return new PageResult<>(
                mapper.findAll(q.environment(), q.businessLine(), q.owner(), status, q.offset(), q.size()),
                mapper.count(q.environment(), q.businessLine(), q.owner(), status), q.page(), q.size());
    }
    @Override
    public boolean update(RedisCluster cluster, long expectedVersion) {
        return mapper.update(ClusterMapper.ClusterRow.from(cluster), expectedVersion) == 1;
    }
    @Override
    public boolean softDelete(long id, long expectedVersion) {
        return mapper.softDelete(id, expectedVersion) == 1;
    }
}
