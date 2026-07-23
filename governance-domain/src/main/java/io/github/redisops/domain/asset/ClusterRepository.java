package io.github.redisops.domain.asset;

import io.github.redisops.common.PageResult;
import java.util.Optional;
import java.util.List;

public interface ClusterRepository {
    RedisCluster save(RedisCluster cluster);
    Optional<RedisCluster> findById(long id);
    PageResult<RedisCluster> findAll(ClusterQuery query);
    boolean update(RedisCluster cluster, long expectedVersion);
    boolean softDelete(long id, long expectedVersion);
}
