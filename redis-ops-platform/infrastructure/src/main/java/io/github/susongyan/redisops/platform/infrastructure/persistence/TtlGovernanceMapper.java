package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.governance.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TtlGovernanceMapper {
    @Insert("INSERT INTO ttl_governance_task(task_no,cluster_id,database_no,include_pattern,target_ttl_seconds,scan_rate_per_second,max_keys,status,approval_status,version) VALUES(#{taskNo},#{clusterId},#{databaseNo},#{includePattern},#{targetTtlSeconds},#{scanRatePerSecond},#{maxKeys},#{status},#{approvalStatus},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(TaskRow row);

    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,target_ttl_seconds targetTtlSeconds,scan_rate_per_second scanRatePerSecond,max_keys maxKeys,status,approval_status approvalStatus,version,created_at createdAt,updated_at updatedAt FROM ttl_governance_task WHERE id=#{id}")
    TtlGovernanceTask task(long id);

    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,target_ttl_seconds targetTtlSeconds,scan_rate_per_second scanRatePerSecond,max_keys maxKeys,status,approval_status approvalStatus,version,created_at createdAt,updated_at updatedAt FROM ttl_governance_task ORDER BY id DESC")
    List<TtlGovernanceTask> tasks();
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,target_ttl_seconds targetTtlSeconds,scan_rate_per_second scanRatePerSecond,max_keys maxKeys,status,approval_status approvalStatus,version,created_at createdAt,updated_at updatedAt FROM ttl_governance_task WHERE status IN ('DRY_RUN','RUNNING') AND cluster_id=#{clusterId} ORDER BY id DESC LIMIT 1")
    TtlGovernanceTask findActiveTaskForCluster(long clusterId);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,target_ttl_seconds targetTtlSeconds,scan_rate_per_second scanRatePerSecond,max_keys maxKeys,status,approval_status approvalStatus,version,created_at createdAt,updated_at updatedAt FROM ttl_governance_task WHERE status IN ('DRY_RUN','RUNNING') AND updated_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL #{minutes} MINUTE)")
    List<TtlGovernanceTask> findStaleQueuedTasks(@Param("minutes") int minutes);

    @Update("UPDATE ttl_governance_task SET status=#{status},approval_status=#{approvalStatus},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateTask(TaskRow row);

    @Insert("INSERT INTO ttl_governance_run(task_id,run_no,status,planned_keys,scanned_keys,candidate_keys,applied_keys,skipped_keys,failed_keys,started_at,completed_at,heartbeat_at,error_code) VALUES(#{taskId},#{runNo},#{status},#{plannedKeys},#{scannedKeys},#{candidateKeys},#{appliedKeys},#{skippedKeys},#{failedKeys},#{startedAt},#{completedAt},CURRENT_TIMESTAMP(3),#{errorCode})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRun(RunRow row);

    @Update("UPDATE ttl_governance_run SET status=#{status},planned_keys=#{plannedKeys},scanned_keys=#{scannedKeys},candidate_keys=#{candidateKeys},applied_keys=#{appliedKeys},skipped_keys=#{skippedKeys},failed_keys=#{failedKeys},completed_at=#{completedAt},heartbeat_at=CURRENT_TIMESTAMP(3),error_code=#{errorCode} WHERE id=#{id}")
    void updateRun(RunRow row);

    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,candidate_keys candidateKeys,applied_keys appliedKeys,skipped_keys skippedKeys,failed_keys failedKeys,started_at startedAt,completed_at completedAt,error_code errorCode FROM ttl_governance_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1")
    TtlGovernanceRun latestRun(long taskId);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,candidate_keys candidateKeys,applied_keys appliedKeys,skipped_keys skippedKeys,failed_keys failedKeys,started_at startedAt,completed_at completedAt,error_code errorCode FROM ttl_governance_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 20")
    List<TtlGovernanceRun> findRuns(long taskId);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,candidate_keys candidateKeys,applied_keys appliedKeys,skipped_keys skippedKeys,failed_keys failedKeys,started_at startedAt,completed_at completedAt,error_code errorCode FROM ttl_governance_run WHERE task_id=#{taskId} AND status IN ('DRY_RUN','RUNNING') AND COALESCE(heartbeat_at,started_at) >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL #{seconds} SECOND) ORDER BY id DESC LIMIT 1")
    TtlGovernanceRun findRecentlyActiveRun(@Param("taskId") long taskId, @Param("seconds") int seconds);
    // paused tasks keep their run row in phase status with a frozen heartbeat: resume reuses that
    // checkpoint, so only runs of tasks still believed executing count as executor-lost
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,candidate_keys candidateKeys,applied_keys appliedKeys,skipped_keys skippedKeys,failed_keys failedKeys,started_at startedAt,completed_at completedAt,error_code errorCode FROM ttl_governance_run r WHERE r.status IN ('DRY_RUN','RUNNING') AND COALESCE(r.heartbeat_at,r.started_at) < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL #{minutes} MINUTE) AND EXISTS (SELECT 1 FROM ttl_governance_task t WHERE t.id=r.task_id AND t.status IN ('DRY_RUN','RUNNING'))")
    List<TtlGovernanceRun> findStaleRuns(@Param("minutes") int minutes);
    @Update("UPDATE ttl_governance_run SET status='FAILED',completed_at=#{completedAt},error_code=#{errorCode} WHERE id=#{id} AND status IN ('DRY_RUN','RUNNING')")
    int failRun(@Param("id") long id, @Param("completedAt") Instant completedAt, @Param("errorCode") String errorCode);
    @Update("UPDATE ttl_governance_run SET heartbeat_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status IN ('DRY_RUN','RUNNING')")
    int touchRun(@Param("id") long id);

    @Insert("INSERT INTO ttl_governance_checkpoint(run_id,shard_id,cursor_value,scanned_keys,status) VALUES(#{runId},#{shardId},#{cursor},#{scannedKeys},#{status}) ON DUPLICATE KEY UPDATE cursor_value=VALUES(cursor_value),scanned_keys=VALUES(scanned_keys),status=VALUES(status),updated_at=CURRENT_TIMESTAMP(3)")
    void upsertCheckpoint(CheckpointRow row);

    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM ttl_governance_checkpoint WHERE run_id=#{runId} AND shard_id=#{shardId}")
    TtlGovernanceCheckpoint checkpoint(@Param("runId") long runId, @Param("shardId") String shardId);

    @Select("SELECT run_id runId,shard_id shardId,cursor_value AS `cursor`,scanned_keys scannedKeys,status,updated_at updatedAt FROM ttl_governance_checkpoint WHERE run_id=#{runId} ORDER BY id")
    List<TtlGovernanceCheckpoint> checkpoints(long runId);

    // Run finalization must also close shard rows: a run that ends by hitting its key cap stops
    // mid-scan, so the last per-batch write leaves shards stuck on RUNNING forever.
    @Update("UPDATE ttl_governance_checkpoint SET status=#{status},updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE run_id=#{runId} AND status='RUNNING'")
    int finalizeCheckpoints(@Param("runId") long runId, @Param("status") String status);

    class TaskRow {
        public Long id;
        public String taskNo, includePattern, status, approvalStatus;
        public long clusterId, targetTtlSeconds, maxKeys, version;
        public int databaseNo, scanRatePerSecond;
    }
    class RunRow {
        public Long id;
        public long taskId, plannedKeys, scannedKeys, candidateKeys, appliedKeys, skippedKeys, failedKeys;
        public String runNo, status, errorCode;
        public Instant startedAt, completedAt;
    }
    class CheckpointRow {
        public long runId, scannedKeys;
        public String shardId, cursor, status;
    }
}
