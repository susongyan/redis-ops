package io.github.redisops.domain.asset;

import java.util.List;
import java.util.Optional;

public interface ClusterSecretRepository {
    Optional<RedisClusterSecret> findByClusterId(long clusterId);
    void save(RedisClusterSecret secret);
    void deleteByClusterId(long clusterId);
    List<RedisClusterSecret> findForReencryption(String activeKeyId, int limit);
    boolean rotate(long clusterId, String expectedKeyId, byte[] encryptedSecret, String activeKeyId);
}
