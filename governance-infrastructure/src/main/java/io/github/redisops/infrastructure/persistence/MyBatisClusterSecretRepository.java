package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.asset.*;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisClusterSecretRepository implements ClusterSecretRepository {
    private final ClusterSecretMapper mapper;
    public MyBatisClusterSecretRepository(ClusterSecretMapper mapper) { this.mapper=mapper; }

    @Override public Optional<RedisClusterSecret> findByClusterId(long clusterId){return Optional.ofNullable(mapper.find(clusterId)).map(MyBatisClusterSecretRepository::secret);}
    @Override public void save(RedisClusterSecret secret){mapper.save(row(secret));}
    @Override public void deleteByClusterId(long clusterId){mapper.delete(clusterId);}
    @Override public List<RedisClusterSecret> findForReencryption(String activeKeyId,int limit){return mapper.findForReencryption(activeKeyId,limit).stream().map(MyBatisClusterSecretRepository::secret).toList();}
    @Override public boolean rotate(long clusterId,String expectedKeyId,byte[] encryptedSecret,String activeKeyId){return mapper.rotate(clusterId,expectedKeyId,encryptedSecret,activeKeyId)==1;}

    private static ClusterSecretMapper.SecretRow row(RedisClusterSecret secret){ClusterSecretMapper.SecretRow row=new ClusterSecretMapper.SecretRow();row.clusterId=secret.clusterId();row.secretUuid=secret.secretUuid().toString();row.encryptedSecret=secret.encryptedSecret();row.keyId=secret.keyId();row.username=secret.username();row.secretStatus=secret.secretStatus().name();row.version=secret.version();return row;}
    private static RedisClusterSecret secret(ClusterSecretMapper.SecretRow row){return new RedisClusterSecret(row.clusterId,UUID.fromString(row.secretUuid),row.encryptedSecret,row.keyId,row.username,SecretStatus.valueOf(row.secretStatus),row.version);}
}
