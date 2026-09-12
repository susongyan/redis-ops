package io.github.redisops.worker.persistence;

import io.github.redisops.worker.domain.WorkerClusterView;
import io.github.redisops.worker.domain.WorkerRedisNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Read-only Worker access to shared Redis asset tables. */
@Mapper
interface WorkerAssetMapper {
    @Select("""
            SELECT id,mode,redis_version AS redisVersion,endpoint,status
            FROM redis_cluster WHERE id=#{clusterId} AND deleted_at IS NULL
            """)
    WorkerClusterView findCluster(@Param("clusterId") long clusterId);

    @Select("""
            SELECT host,port,role FROM redis_node
            WHERE cluster_id=#{clusterId} ORDER BY host,port
            """)
    List<WorkerRedisNode> findNodes(@Param("clusterId") long clusterId);

    @Select("""
            SELECT secret_uuid AS secretUuid,encrypted_secret AS encryptedSecret,key_id AS keyId,username,
              secret_status AS secretStatus
            FROM redis_cluster_secret WHERE cluster_id=#{clusterId}
            """)
    WorkerCredentialRow findCredential(@Param("clusterId") long clusterId);

    class WorkerCredentialRow {
        public java.util.UUID secretUuid;
        public byte[] encryptedSecret;
        public String keyId, username, secretStatus;
    }
}
