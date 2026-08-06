package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.governance.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CleanupGovernanceMapper {
    @Insert("INSERT INTO cleanup_governance_task(task_no,cluster_id,database_no,include_pattern,impact_limit,scan_rate_per_second,status,approval_status,approval_note,version) VALUES(#{taskNo},#{clusterId},#{databaseNo},#{includePattern},#{impactLimit},#{scanRatePerSecond},#{status},#{approvalStatus},#{approvalNote},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(TaskRow row);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,impact_limit impactLimit,scan_rate_per_second scanRatePerSecond,status,approval_status approvalStatus,approval_note approvalNote,version,created_at createdAt,updated_at updatedAt FROM cleanup_governance_task WHERE id=#{id}")
    CleanupGovernanceTask task(long id);
    @Select("SELECT id,task_no taskNo,cluster_id clusterId,database_no databaseNo,include_pattern includePattern,impact_limit impactLimit,scan_rate_per_second scanRatePerSecond,status,approval_status approvalStatus,approval_note approvalNote,version,created_at createdAt,updated_at updatedAt FROM cleanup_governance_task ORDER BY id DESC")
    List<CleanupGovernanceTask> tasks();
    @Update("UPDATE cleanup_governance_task SET status=#{status},approval_status=#{approvalStatus},approval_note=#{approvalNote},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateTask(TaskRow row);
    @Insert("INSERT INTO cleanup_governance_run(task_id,run_no,status,planned_keys,scanned_keys,candidate_keys,deleted_keys,skipped_keys,failed_keys,started_at,completed_at,error_code) VALUES(#{taskId},#{runNo},#{status},#{plannedKeys},#{scannedKeys},#{candidateKeys},#{deletedKeys},#{skippedKeys},#{failedKeys},#{startedAt},#{completedAt},#{errorCode})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRun(RunRow row);
    @Update("UPDATE cleanup_governance_run SET status=#{status},planned_keys=#{plannedKeys},scanned_keys=#{scannedKeys},candidate_keys=#{candidateKeys},deleted_keys=#{deletedKeys},skipped_keys=#{skippedKeys},failed_keys=#{failedKeys},completed_at=#{completedAt},error_code=#{errorCode} WHERE id=#{id}")
    void updateRun(RunRow row);
    @Select("SELECT id,task_id taskId,run_no runNo,status,planned_keys plannedKeys,scanned_keys scannedKeys,candidate_keys candidateKeys,deleted_keys deletedKeys,skipped_keys skippedKeys,failed_keys failedKeys,started_at startedAt,completed_at completedAt,error_code errorCode FROM cleanup_governance_run WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1")
    CleanupGovernanceRun latestRun(long taskId);
    @Insert("INSERT INTO cleanup_governance_checkpoint(run_id,shard_id,cursor_value,scanned_keys,status) VALUES(#{runId},#{shardId},#{cursor},#{scannedKeys},#{status}) ON DUPLICATE KEY UPDATE cursor_value=VALUES(cursor_value),scanned_keys=VALUES(scanned_keys),status=VALUES(status),updated_at=CURRENT_TIMESTAMP(3)")
    void upsertCheckpoint(CheckpointRow row);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value cursor,scanned_keys scannedKeys,status,updated_at updatedAt FROM cleanup_governance_checkpoint WHERE run_id=#{runId} AND shard_id=#{shardId}")
    CleanupGovernanceCheckpoint checkpoint(@Param("runId") long runId, @Param("shardId") String shardId);
    @Select("SELECT run_id runId,shard_id shardId,cursor_value cursor,scanned_keys scannedKeys,status,updated_at updatedAt FROM cleanup_governance_checkpoint WHERE run_id=#{runId} ORDER BY id")
    List<CleanupGovernanceCheckpoint> checkpoints(long runId);
    class TaskRow {
        public Long id;
        public String taskNo, includePattern, status, approvalStatus, approvalNote;
        public long clusterId, impactLimit, version;
        public int databaseNo, scanRatePerSecond;
    }
    class RunRow {
        public Long id;
        public long taskId, plannedKeys, scannedKeys, candidateKeys, deletedKeys, skippedKeys, failedKeys;
        public String runNo, status, errorCode;
        public Instant startedAt, completedAt;
    }
    class CheckpointRow {
        public long runId, scannedKeys;
        public String shardId, cursor, status;
    }
}
