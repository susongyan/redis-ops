package io.github.susongyan.redisops.platform.infrastructure.persistence;
import io.github.susongyan.redisops.platform.domain.risk.*;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface RiskScanMapper {
    @Insert("INSERT INTO scan_task(task_no,cluster_id,database_no,include_pattern,check_large_key,check_no_ttl,large_key_threshold_bytes,scan_rate_per_second,max_findings,group_delimiter,group_segments,status,version) VALUES(#{taskNo},#{clusterId},#{databaseNo},#{includePattern},#{checkLargeKey},#{checkNoTtl},#{largeKeyThresholdBytes},#{scanRatePerSecond},#{maxFindings},#{groupDelimiter},#{groupSegments},#{status},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(RiskScanTaskRow row);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,group_delimiter groupDelimiter,group_segments groupSegments,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE id=#{id} AND deleted_at IS NULL")
    RiskScanTask findTask(long id);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,group_delimiter groupDelimiter,group_segments groupSegments,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE deleted_at IS NULL ORDER BY id DESC")
    List<RiskScanTask> findTasks();
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,group_delimiter groupDelimiter,group_segments groupSegments,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE deleted_at IS NULL AND status='RUNNING' AND cluster_id=#{clusterId} ORDER BY id DESC LIMIT 1")
    RiskScanTask findActiveTaskForCluster(long clusterId);
    @Update("UPDATE scan_task SET status=#{status},version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int updateTask(RiskScanTask task);
    @Insert("INSERT INTO scan_run(task_id,run_no,status,planned_keys,scanned_keys,finding_count,no_ttl_total,large_key_total,started_at,completed_at,error_code,heartbeat_at) VALUES(#{taskId},#{runNo},#{status},#{plannedKeys},#{scannedKeys},#{findingCount},#{noTtlTotal},#{largeKeyTotal},#{startedAt},#{completedAt},#{errorCode},#{heartbeat})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRun(RiskScanRunRow row);
    @Update("UPDATE scan_run SET status=#{status},planned_keys=#{plannedKeys},scanned_keys=#{scannedKeys},finding_count=#{findingCount},no_ttl_total=#{noTtlTotal},large_key_total=#{largeKeyTotal},completed_at=#{completedAt},error_code=#{errorCode},heartbeat_at=#{heartbeat} WHERE id=#{id}")
    int updateRun(RiskScanRunRow row);
    @Update("UPDATE scan_run SET status='FAILED',completed_at=#{completedAt},error_code='SCAN_EXECUTOR_LOST' WHERE id=#{id} AND status='RUNNING'")
    int failRun(@Param("id") long id, @Param("completedAt") java.time.Instant completedAt);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,group_delimiter groupDelimiter,group_segments groupSegments,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE deleted_at IS NULL AND status='RUNNING' AND updated_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL #{minutes} MINUTE)")
    List<RiskScanTask> findStaleQueuedTasks(@Param("minutes") int minutes);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,finding_count findingCount,no_ttl_total noTtlTotal,large_key_total largeKeyTotal,started_at startedAt,completed_at completedAt,error_code errorCode FROM scan_run WHERE status='RUNNING' AND COALESCE(heartbeat_at,started_at) < #{threshold}")
    List<RiskScanRun> findStaleRuns(@Param("threshold") java.time.Instant threshold);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,finding_count findingCount,no_ttl_total noTtlTotal,large_key_total largeKeyTotal,started_at startedAt,completed_at completedAt,error_code errorCode FROM scan_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1")
    RiskScanRun latestRun(long taskId);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM scan_shard_checkpoint WHERE run_id=#{runId} AND shard_id=#{shardId}")
    RiskScanCheckpoint checkpoint(@Param("runId") long runId, @Param("shardId") String shardId);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM scan_shard_checkpoint WHERE run_id=#{runId} ORDER BY shard_id")
    List<RiskScanCheckpoint> checkpoints(long runId);
    @Insert("INSERT INTO scan_shard_checkpoint(run_id,shard_id,cursor_value,scanned_keys,status) VALUES(#{runId},#{shardId},#{cursor},#{scannedKeys},#{status}) ON DUPLICATE KEY UPDATE cursor_value=VALUES(cursor_value),scanned_keys=VALUES(scanned_keys),status=VALUES(status)")
    void saveCheckpoint(RiskScanCheckpoint checkpoint);
    @Insert("INSERT INTO risk_finding(run_id,risk_type,risk_level,key_name,key_hash,redis_type,memory_bytes,element_count,ttl_seconds,node_id,created_at) VALUES(#{runId},#{riskType},#{riskLevel},#{keyName},#{keyHash},#{redisType},#{memoryBytes},#{elementCount},#{ttlSeconds},#{nodeId},#{createdAt})")
    void insertFinding(RiskFinding finding);
    @Insert("""
            <script>
            INSERT INTO risk_finding(run_id,risk_type,risk_level,key_name,key_hash,redis_type,memory_bytes,element_count,ttl_seconds,node_id,created_at) VALUES
            <foreach collection="findings" item="f" separator=",">(#{f.runId},#{f.riskType},#{f.riskLevel},#{f.keyName},#{f.keyHash},#{f.redisType},#{f.memoryBytes},#{f.elementCount},#{f.ttlSeconds},#{f.nodeId},#{f.createdAt})</foreach>
            </script>
            """)
    void insertFindings(@Param("findings") List<RiskFinding> findings);
    @Insert("""
            <script>
            INSERT INTO risk_finding_group(run_id,risk_type,group_text,hit_count,sample_key,max_memory_bytes) VALUES
            <foreach collection="groups" item="g" separator=",">(#{g.runId},#{g.riskType},#{g.groupText},#{g.hitCount},#{g.sampleKey},#{g.maxMemoryBytes})</foreach>
            ON DUPLICATE KEY UPDATE hit_count=hit_count+VALUES(hit_count),
              max_memory_bytes=GREATEST(COALESCE(max_memory_bytes,0),COALESCE(VALUES(max_memory_bytes),0))
            </script>
            """)
    void upsertGroups(@Param("groups") List<RiskFindingGroup> groups);
    @Select("SELECT group_text FROM risk_finding_group WHERE run_id=#{runId}")
    List<String> groupTexts(long runId);
    @Select("SELECT id,run_id runId,risk_type riskType,group_text groupText,hit_count hitCount,sample_key sampleKey,max_memory_bytes maxMemoryBytes FROM risk_finding_group WHERE run_id=#{runId} ORDER BY hit_count DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<RiskFindingGroupRow> findGroups(@Param("runId") long runId, @Param("offset") int offset,
            @Param("size") int size);
    @Select("SELECT COUNT(*) FROM risk_finding_group WHERE run_id=#{runId}")
    long groupCount(long runId);
    @Select("""
            <script>
            SELECT id,run_id runId,risk_type riskType,risk_level riskLevel,key_name keyName,key_hash keyHash,
                   redis_type redisType,memory_bytes memoryBytes,element_count elementCount,ttl_seconds ttlSeconds,
                   node_id nodeId,created_at createdAt
            FROM risk_finding WHERE run_id=#{runId}
            <if test="riskType != null and riskType != ''">AND risk_type=#{riskType}</if>
            ORDER BY memory_bytes DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<RiskFinding> findings(@Param("runId") long runId, @Param("offset") int offset, @Param("size") int size,
            @Param("riskType") String riskType);
    @Select("<script>SELECT COUNT(*) FROM risk_finding WHERE run_id=#{runId}<if test=\"riskType != null and riskType != ''\"> AND risk_type=#{riskType}</if></script>")
    long findingCount(@Param("runId") long runId, @Param("riskType") String riskType);
    @Select("SELECT COUNT(*) FROM risk_finding WHERE run_id=#{runId} AND risk_type=#{riskType}")
    long findingCountByType(@Param("runId") long runId, @Param("riskType") String riskType);
    class RiskScanTaskRow {
        public Long id;
        public String taskNo, includePattern, status, groupDelimiter;
        public boolean checkLargeKey, checkNoTtl;
        public long clusterId, largeKeyThresholdBytes;
        public int databaseNo, scanRatePerSecond, maxFindings, groupSegments;
    }
    class RiskScanRunRow {
        public Long id;
        public long taskId, plannedKeys, scannedKeys, findingCount;
        public Long noTtlTotal, largeKeyTotal;
        public String runNo, status, errorCode;
        public java.time.Instant startedAt, completedAt, heartbeat;
    }
    class RiskFindingGroupRow {
        public long runId;
        public long hitCount;
        public String riskType, groupText, sampleKey;
        public Long maxMemoryBytes;
    }
}
