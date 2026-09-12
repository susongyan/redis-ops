package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.asset.RedisCluster;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ClusterMapper {
    @Insert("""
            INSERT INTO redis_cluster(name,environment,business_line,owner,ops_owner,service_level,mode,
              redis_version,endpoint,idc_id,status,version)
            VALUES(#{name},#{environment},#{businessLine},#{owner},#{opsOwner},#{serviceLevel},#{mode},
              #{redisVersion},#{endpoint},#{idcId},#{status},0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ClusterRow row);

    @Select("""
            SELECT id,name,environment,business_line,owner,ops_owner,service_level,mode,redis_version,
              endpoint,idc_id,status,version,created_at,updated_at
            FROM redis_cluster WHERE id=#{id} AND deleted_at IS NULL
            """)
    RedisCluster findById(long id);

    @Select("""
            <script>SELECT id,name,environment,business_line,owner,ops_owner,service_level,mode,redis_version,
              endpoint,idc_id,status,version,created_at,updated_at
            FROM redis_cluster WHERE deleted_at IS NULL
            <if test='environment != null and environment != ""'> AND environment=#{environment}</if>
            <if test='businessLine != null and businessLine != ""'> AND business_line=#{businessLine}</if>
            <if test='owner != null and owner != ""'> AND owner=#{owner}</if>
            <if test='status != null'> AND status=#{status}</if>
            ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<RedisCluster> findAll(@Param("environment") String environment,
            @Param("businessLine") String businessLine,
            @Param("owner") String owner, @Param("status") String status,
            @Param("offset") int offset, @Param("size") int size);

    @Select("""
            <script>SELECT COUNT(*) FROM redis_cluster WHERE deleted_at IS NULL
            <if test='environment != null and environment != ""'> AND environment=#{environment}</if>
            <if test='businessLine != null and businessLine != ""'> AND business_line=#{businessLine}</if>
            <if test='owner != null and owner != ""'> AND owner=#{owner}</if>
            <if test='status != null'> AND status=#{status}</if></script>
            """)
    long count(@Param("environment") String environment, @Param("businessLine") String businessLine,
            @Param("owner") String owner, @Param("status") String status);

    @Update("""
            UPDATE redis_cluster SET name=#{row.name},environment=#{row.environment},business_line=#{row.businessLine},
              owner=#{row.owner},ops_owner=#{row.opsOwner},service_level=#{row.serviceLevel},mode=#{row.mode},
              redis_version=#{row.redisVersion},endpoint=#{row.endpoint},
              idc_id=#{row.idcId},status=#{row.status},version=version+1
            WHERE id=#{row.id} AND version=#{expectedVersion} AND deleted_at IS NULL
            """)
    int update(@Param("row") ClusterRow row, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE redis_cluster SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int softDelete(@Param("id") long id, @Param("version") long version);

    class ClusterRow {
        public Long id;
        public String name;
        public String environment;
        public String businessLine;
        public String owner;
        public String opsOwner;
        public String serviceLevel;
        public String mode;
        public String redisVersion;
        public String endpoint;
        public Long idcId;
        public String status;
        static ClusterRow from(RedisCluster c) {
            ClusterRow r = new ClusterRow();
            r.id = c.id();
            r.name = c.name();
            r.environment = c.environment();
            r.businessLine = c.businessLine();
            r.owner = c.owner();
            r.opsOwner = c.opsOwner();
            r.serviceLevel = c.serviceLevel();
            r.mode = c.mode().name();
            r.redisVersion = c.redisVersion();
            r.endpoint = c.endpoint();
            r.idcId = c.idcId();
            r.status = c.status().name();
            return r;
        }
    }
}
