package io.github.redisops.infrastructure.persistence;
import io.github.redisops.domain.risk.*;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface RiskScanMapper {
    @Insert("INSERT INTO scan_task(task_no,cluster_id,database_no,include_pattern,check_large_key,check_no_ttl,large_key_threshold_bytes,scan_rate_per_second,max_findings,status,version) VALUES(#{taskNo},#{clusterId},#{databaseNo},#{includePattern},#{checkLargeKey},#{checkNoTtl},#{largeKeyThresholdBytes},#{scanRatePerSecond},#{maxFindings},#{status},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(RiskScanTaskRow row);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE id=#{id} AND deleted_at IS NULL")
    RiskScanTask findTask(long id);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,check_large_key checkLargeKey,check_no_ttl checkNoTtl,large_key_threshold_bytes largeKeyThresholdBytes,scan_rate_per_second scanRatePerSecond,max_findings maxFindings,status,version,created_at createdAt,updated_at updatedAt FROM scan_task WHERE deleted_at IS NULL ORDER BY id DESC")
    List<RiskScanTask> findTasks();
    @Update("UPDATE scan_task SET status=#{status},version=version+1 WHERE id=#{id} AND version=#{version} AND deleted_at IS NULL")
    int updateTask(RiskScanTask task);
    @Insert("INSERT INTO scan_run(task_id,run_no,status,planned_keys,scanned_keys,finding_count,started_at,completed_at,error_code) VALUES(#{taskId},#{runNo},#{status},#{plannedKeys},#{scannedKeys},#{findingCount},#{startedAt},#{completedAt},#{errorCode})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRun(RiskScanRunRow row);
    @Update("UPDATE scan_run SET status=#{status},planned_keys=#{plannedKeys},scanned_keys=#{scannedKeys},finding_count=#{findingCount},completed_at=#{completedAt},error_code=#{errorCode} WHERE id=#{id}")
    int updateRun(RiskScanRun run);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,finding_count findingCount,started_at startedAt,completed_at completedAt,error_code errorCode FROM scan_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1")
    RiskScanRun latestRun(long taskId);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM scan_shard_checkpoint WHERE run_id=#{runId} AND shard_id=#{shardId}")
    RiskScanCheckpoint checkpoint(@Param("runId") long runId, @Param("shardId") String shardId);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM scan_shard_checkpoint WHERE run_id=#{runId} ORDER BY shard_id")
    List<RiskScanCheckpoint> checkpoints(long runId);
    @Insert("INSERT INTO scan_shard_checkpoint(run_id,shard_id,cursor_value,scanned_keys,status) VALUES(#{runId},#{shardId},#{cursor},#{scannedKeys},#{status}) ON DUPLICATE KEY UPDATE cursor_value=VALUES(cursor_value),scanned_keys=VALUES(scanned_keys),status=VALUES(status)")
    void saveCheckpoint(RiskScanCheckpoint checkpoint);
    @Insert("INSERT INTO risk_finding(run_id,risk_type,risk_level,key_name,key_hash,redis_type,memory_bytes,element_count,ttl_seconds,node_id,created_at) VALUES(#{runId},#{riskType},#{riskLevel},#{keyName},#{keyHash},#{redisType},#{memoryBytes},#{elementCount},#{ttlSeconds},#{nodeId},#{createdAt})")
    void insertFinding(RiskFinding finding);
    @Select("""
            <script>
            SELECT id,run_id runId,risk_type riskType,risk_level riskLevel,key_name keyName,key_hash keyHash,
                   redis_type redisType,memory_bytes memoryBytes,element_count elementCount,ttl_seconds ttlSeconds,
                   node_id nodeId,created_at createdAt
            FROM risk_finding WHERE run_id=#{runId}
            <if test=\"riskType != null and riskType != ''\">AND risk_type=#{riskType}</if>
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
        public String taskNo, includePattern, status;
        public boolean checkLargeKey, checkNoTtl;
        public long clusterId, largeKeyThresholdBytes;
        public int databaseNo, scanRatePerSecond, maxFindings;
    }
    class RiskScanRunRow {
        public Long id;
        public long taskId, plannedKeys, scannedKeys, findingCount;
        public String runNo, status, errorCode;
        public java.time.Instant startedAt, completedAt;
    }
}
