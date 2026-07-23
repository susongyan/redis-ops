package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditLog;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AssetMapper {
    @Insert("INSERT INTO application(app_code,name,owner,business_line,status) VALUES(#{code},#{name},#{owner},#{businessLine},#{status})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertApplication(ApplicationRow row);
    @Select("SELECT id,app_code AS code,name,owner,business_line,status,version FROM application WHERE id=#{id} AND deleted_at IS NULL") ManagedApplication findApplication(long id);
    @Select("SELECT id,app_code AS code,name,owner,business_line,status,version FROM application WHERE deleted_at IS NULL ORDER BY id DESC") List<ManagedApplication> findApplications();
    @Update("UPDATE application SET app_code=#{row.code},name=#{row.name},owner=#{row.owner},business_line=#{row.businessLine},status=#{row.status},version=version+1 WHERE id=#{row.id} AND version=#{version} AND deleted_at IS NULL")
    int updateApplication(@Param("row") ApplicationRow row,@Param("version") long version);
    @Update("UPDATE application SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int deleteApplication(@Param("id") long id,@Param("version") long version);

    @Insert("""
        INSERT INTO app_cluster_binding(application_id,cluster_id,client_type,client_version,pool_config_json)
        VALUES(#{applicationId},#{clusterId},#{clientType},#{clientVersion},CAST(#{poolConfig} AS JSON))
        ON DUPLICATE KEY UPDATE client_type=VALUES(client_type),client_version=VALUES(client_version),
          pool_config_json=VALUES(pool_config_json)
        """) void bind(ApplicationBinding binding);
    @Delete("DELETE FROM app_cluster_binding WHERE application_id=#{applicationId} AND cluster_id=#{clusterId}")
    void unbind(@Param("applicationId") long applicationId, @Param("clusterId") long clusterId);
    @Select("SELECT b.application_id,b.cluster_id,b.client_type,b.client_version,CAST(b.pool_config_json AS CHAR) pool_config FROM app_cluster_binding b JOIN application a ON a.id=b.application_id AND a.deleted_at IS NULL WHERE b.cluster_id=#{clusterId}")
    List<ApplicationBinding> findBindings(long clusterId);
    @Select("SELECT application_id,cluster_id,client_type,client_version,CAST(pool_config_json AS CHAR) pool_config FROM app_cluster_binding WHERE application_id=#{applicationId}")
    List<ApplicationBinding> findApplicationBindings(long applicationId);

    @Select("SELECT id,cluster_id,host,port,node_id,role,master_node_id,CAST(slot_ranges_json AS CHAR) slot_ranges,memory_bytes,status FROM redis_node WHERE cluster_id=#{clusterId} ORDER BY host,port")
    List<RedisNode> findNodes(long clusterId);
    @Delete("DELETE FROM redis_node WHERE cluster_id=#{clusterId}") void deleteNodes(long clusterId);
    @Insert("""
        INSERT INTO redis_node(cluster_id,host,port,node_id,role,master_node_id,slot_ranges_json,memory_bytes,status)
        VALUES(#{clusterId},#{host},#{port},#{nodeId},#{role},#{masterNodeId},CAST(#{slotRanges} AS JSON),#{memoryBytes},#{status})
        """) void insertNode(RedisNode node);

    @Insert("INSERT INTO discovery_run(cluster_id,status,started_at) VALUES(#{clusterId},'RUNNING',CURRENT_TIMESTAMP(3))")
    @Options(useGeneratedKeys=true,keyProperty="id") void startDiscovery(DiscoveryRow row);
    @Update("UPDATE discovery_run SET status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP(3),node_count=#{count} WHERE id=#{id}")
    void completeDiscovery(@Param("id") long id, @Param("count") int count);
    @Update("UPDATE discovery_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP(3),error_message=#{error} WHERE id=#{id}")
    void failDiscovery(@Param("id") long id, @Param("error") String error);
    @Select("SELECT id,cluster_id,status,started_at,finished_at,node_count,error_message FROM discovery_run WHERE cluster_id=#{clusterId} ORDER BY id DESC LIMIT 100")
    List<DiscoveryRun> findDiscoveries(long clusterId);
    @Select("SELECT id,cluster_id,status,started_at,finished_at,node_count,error_message FROM discovery_run WHERE id=#{id}")
    DiscoveryRun findDiscovery(long id);
    @Update("UPDATE discovery_run SET status='RUNNING',finished_at=NULL,node_count=NULL,error_message=NULL WHERE id=#{id}")
    void restartDiscovery(long id);

    @Insert("INSERT INTO audit_log(operator_id,action,resource_type,resource_id,result) VALUES(#{operator},#{action},#{resourceType},#{resourceId},#{result})")
    void appendAudit(@Param("operator") String operator, @Param("action") String action,
                     @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
                     @Param("result") String result);
    @Select("""
        <script>
        SELECT id,operator_id AS operator,action,resource_type,resource_id,result,request_id,request_digest,created_at
        FROM audit_log
        WHERE 1=1
        <if test='operator != null'> AND operator_id=#{operator}</if>
        <if test='resourceType != null'> AND resource_type=#{resourceType}</if>
        <if test='resourceId != null'> AND resource_id=#{resourceId}</if>
        ORDER BY id DESC LIMIT #{limit}
        </script>
        """)
    List<AuditLog> findAudits(@Param("operator") String operator,
                              @Param("resourceType") String resourceType,
                              @Param("resourceId") String resourceId,
                              @Param("limit") int limit);

    class ApplicationRow { public Long id; public String code,name,owner,businessLine,status; public long version; }
    class DiscoveryRow { public Long id; public long clusterId; }
}
