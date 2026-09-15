package io.github.susongyan.redisops.platform.domain.asset;

import io.github.susongyan.redisops.platform.common.PageResult;
import java.util.Optional;

public interface ClusterRepository {
    RedisCluster save(RedisCluster cluster);
    Optional<RedisCluster> findById(long id);
    PageResult<RedisCluster> findAll(ClusterQuery query);
    boolean update(RedisCluster cluster, long expectedVersion);
    boolean softDelete(long id, long expectedVersion);
}
