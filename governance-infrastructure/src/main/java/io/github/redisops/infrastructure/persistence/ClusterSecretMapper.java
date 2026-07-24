package io.github.redisops.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ClusterSecretMapper {
    @Select("SELECT cluster_id,secret_uuid,encrypted_secret,key_id,username,secret_status,version FROM redis_cluster_secret WHERE cluster_id=#{clusterId}")
    SecretRow find(long clusterId);

    @Insert("""
            INSERT INTO redis_cluster_secret(cluster_id,secret_uuid,encrypted_secret,key_id,username,secret_status,version)
            VALUES(#{clusterId},#{secretUuid},#{encryptedSecret},#{keyId},#{username},#{secretStatus},0)
            ON DUPLICATE KEY UPDATE encrypted_secret=VALUES(encrypted_secret),key_id=VALUES(key_id),
              username=VALUES(username),secret_status=VALUES(secret_status),version=version+1
            """)
    void save(SecretRow row);

    @Delete("DELETE FROM redis_cluster_secret WHERE cluster_id=#{clusterId}")
    void delete(long clusterId);

    @Select("""
            SELECT cluster_id,secret_uuid,encrypted_secret,key_id,username,secret_status,version
            FROM redis_cluster_secret WHERE secret_status='ENCRYPTED' AND key_id<>#{activeKeyId}
            ORDER BY cluster_id LIMIT #{limit}
            """)
    List<SecretRow> findForReencryption(@Param("activeKeyId") String activeKeyId, @Param("limit") int limit);

    @Update("""
            UPDATE redis_cluster_secret SET encrypted_secret=#{encryptedSecret},key_id=#{activeKeyId},version=version+1
            WHERE cluster_id=#{clusterId} AND key_id=#{expectedKeyId} AND secret_status='ENCRYPTED'
            """)
    int rotate(@Param("clusterId") long clusterId, @Param("expectedKeyId") String expectedKeyId,
            @Param("encryptedSecret") byte[] encryptedSecret, @Param("activeKeyId") String activeKeyId);

    class SecretRow {
        public long clusterId;
        public String secretUuid;
        public byte[] encryptedSecret;
        public String keyId, username, secretStatus;
        public long version;
    }
}
