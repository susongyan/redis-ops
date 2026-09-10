package io.github.redisops.sync.worker.persistence;

import io.github.redisops.sync.worker.domain.WorkerSyncRuntime;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;
import io.github.redisops.sync.worker.domain.WorkerSyncPrecheckReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Worker-owned SQL access to the shared sync control tables. */
@Mapper
public interface WorkerSyncMapper {
    String TASK_COLUMNS = "id,task_no,relation_id,source_cluster_id,target_cluster_id,purpose,sync_mode,status,tool_type,"
            + "source_db,target_db,CAST(include_patterns_json AS CHAR) include_patterns_json,"
            + "CAST(exclude_patterns_json AS CHAR) exclude_patterns_json,"
            + "CAST(command_policy_json AS CHAR) command_policy_json,rate_limit_ops,bandwidth_limit_bytes_per_second,"
            + "spool_limit_bytes,full_apply_concurrency,full_apply_pipeline_size,desired_action,write_fenced,"
            + "write_fence_note,blocked_reason,full_sync_epoch,last_rpo_seconds,last_error,version,created_at,"
            + "updated_at,finished_at";

    @Select("SELECT " + TASK_COLUMNS + " FROM sync_task WHERE id=#{taskId}")
    WorkerSyncTask findTask(@Param("taskId") long taskId);

    @Select("""
            SELECT """ + TASK_COLUMNS + """
            FROM sync_task t JOIN sync_runtime r ON r.task_id=t.id
            WHERE r.lease_owner IS NOT NULL AND r.lease_until<CURRENT_TIMESTAMP(3)
              AND t.status IN ('STARTING','FULL_SYNCING','INCR_SYNCING','CAUGHT_UP','RESUMING')
            ORDER BY r.lease_until,t.id LIMIT #{limit}
            """)
    List<WorkerSyncTask> findExpiredRecoverableTasks(@Param("limit") int limit);

    @Select("""
            SELECT task_id,runtime_id,lease_owner,lease_until,fencing_generation,phase,heartbeat_at,spool_bytes,
              target_fence_generation,fence_published_at,takeover_count,recovery_action,last_error,started_at,updated_at
            FROM sync_runtime WHERE task_id=#{taskId}
            """)
    WorkerSyncRuntime findRuntime(@Param("taskId") long taskId);

    @Insert("INSERT IGNORE INTO sync_runtime(task_id,runtime_id,phase) VALUES(#{taskId},#{runtimeId},'IDLE')")
    void ensureRuntime(@Param("taskId") long taskId, @Param("runtimeId") String runtimeId);

    @Update("""
            UPDATE sync_runtime SET runtime_id=#{runtimeId},
              takeover_count=takeover_count+IF(lease_owner IS NOT NULL AND lease_owner<>#{owner},1,0),
              recovery_action=IF(lease_owner IS NULL,'INITIAL_CLAIM','TAKEOVER_CLAIMED'),
              phase=IF(lease_owner IS NULL,'CLAIMED','TAKEOVER_CLAIMED'),lease_owner=#{owner},
              lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL #{leaseSeconds} SECOND),
              fencing_generation=fencing_generation+1,heartbeat_at=CURRENT_TIMESTAMP(3),
              started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3))
            WHERE task_id=#{taskId} AND (lease_owner=#{owner} OR lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3))
            """)
    int claimRuntime(@Param("taskId") long taskId, @Param("runtimeId") String runtimeId,
            @Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE sync_runtime SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL #{leaseSeconds} SECOND),
              phase=#{phase},spool_bytes=#{spoolBytes},heartbeat_at=CURRENT_TIMESTAMP(3)
            WHERE task_id=#{taskId} AND lease_owner=#{owner} AND lease_until>=CURRENT_TIMESTAMP(3)
            """)
    int renewRuntime(@Param("taskId") long taskId, @Param("owner") String owner,
            @Param("leaseSeconds") long leaseSeconds, @Param("phase") String phase,
            @Param("spoolBytes") long spoolBytes);

    @Update("""
            UPDATE sync_runtime SET lease_owner=NULL,lease_until=NULL,phase=#{phase},last_error=#{error},
              heartbeat_at=CURRENT_TIMESTAMP(3) WHERE task_id=#{taskId} AND lease_owner=#{owner}
            """)
    int releaseRuntime(@Param("taskId") long taskId, @Param("owner") String owner,
            @Param("phase") String phase, @Param("error") String error);

    @Update("""
            UPDATE sync_task SET status=#{status},blocked_reason=#{blockedReason},
              last_rpo_seconds=COALESCE(#{rpoSeconds},last_rpo_seconds),last_error=#{error},
              finished_at=CASE WHEN #{status} IN ('FINISHED','CANCELLED') THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
              version=version+1
            WHERE id=#{taskId} AND version=#{version}
            """)
    int transitionTask(@Param("taskId") long taskId, @Param("version") long version,
            @Param("status") String status, @Param("rpoSeconds") Long rpoSeconds,
            @Param("blockedReason") String blockedReason, @Param("error") String error);

    @Insert("""
            INSERT INTO sync_task_event(task_id,from_status,to_status,operator_id,message)
            VALUES(#{taskId},#{fromStatus},#{toStatus},#{operator},#{message})
            """)
    void insertEvent(@Param("taskId") long taskId, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("operator") String operator,
            @Param("message") String message);

    @Update("""
            UPDATE sync_runtime SET phase=#{phase},
              target_fence_generation=COALESCE(#{targetFenceGeneration},target_fence_generation),
              fence_published_at=COALESCE(#{fencePublishedAt},fence_published_at),
              recovery_action=COALESCE(#{recoveryAction},recovery_action),last_error=#{error}
            WHERE task_id=#{taskId} AND lease_owner=#{owner}
            """)
    int updateRuntimeObservation(@Param("taskId") long taskId, @Param("owner") String owner,
            @Param("phase") String phase, @Param("targetFenceGeneration") Long targetFenceGeneration,
            @Param("fencePublishedAt") java.time.Instant fencePublishedAt,
            @Param("recoveryAction") String recoveryAction, @Param("error") String error);

    @Insert("""
            INSERT INTO sync_precheck_report(task_id,status,report_json,checked_at,valid_until)
            VALUES(#{taskId},#{status},CAST(#{reportJson} AS JSON),#{checkedAt},#{validUntil})
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    void insertPrecheck(PrecheckRow row);

    @Insert("""
            INSERT INTO sync_channel_checkpoint(task_id,channel_id,source_node_id,slot_ranges,replication_id,
              received_offset,applied_offset,status,last_heartbeat_at)
            VALUES(#{taskId},#{channelId},#{sourceNode},#{slotRangesJson},#{replicationId},#{receivedOffset},
              #{appliedOffset},#{status},#{updatedAt})
            ON DUPLICATE KEY UPDATE source_node_id=VALUES(source_node_id),slot_ranges=VALUES(slot_ranges),
              replication_id=VALUES(replication_id),received_offset=VALUES(received_offset),
              applied_offset=VALUES(applied_offset),status=VALUES(status),last_heartbeat_at=VALUES(last_heartbeat_at)
            """)
    void upsertChannel(io.github.redisops.sync.worker.domain.WorkerSyncChannelCheckpoint checkpoint);

    @Insert("""
            INSERT INTO sync_metric_snapshot(task_id,channel_id,timestamp_lag_seconds,estimated_lag_seconds,
              offset_gap_bytes,backlog_bytes,source_bytes_per_second,target_apply_bytes_per_second,
              catch_up_eta_seconds,calculation_method,confidence,collected_at)
            VALUES(#{taskId},#{channelId},#{rpoSeconds},#{estimatedLagSeconds},#{replicationOffsetGap},#{spoolBytes},
              #{sourceBytesPerSecond},#{targetBytesPerSecond},#{catchUpEtaSeconds},#{rpoMethod},#{confidence},#{capturedAt})
            """)
    void insertMetric(io.github.redisops.sync.worker.domain.WorkerSyncMetricSnapshot metric);

    @Insert("""
            INSERT INTO sync_full_progress(task_id,full_sync_epoch,channel_id,lane,stage,total_bytes,received_bytes,
              parsed_bytes,total_keys,parsed_keys,applied_keys,applied_bytes,status,started_at)
            VALUES(#{taskId},#{fullSyncEpoch},#{channelId},#{lane},#{stage},#{totalBytes},#{receivedBytes},
              #{parsedBytes},#{totalKeys},#{parsedKeys},#{appliedKeys},#{appliedBytes},#{status},#{startedAt})
            ON DUPLICATE KEY UPDATE stage=VALUES(stage),total_bytes=COALESCE(VALUES(total_bytes),total_bytes),
              received_bytes=GREATEST(received_bytes,VALUES(received_bytes)),
              parsed_bytes=GREATEST(parsed_bytes,VALUES(parsed_bytes)),
              total_keys=COALESCE(VALUES(total_keys),total_keys),
              parsed_keys=GREATEST(parsed_keys,VALUES(parsed_keys)),
              applied_keys=GREATEST(applied_keys,VALUES(applied_keys)),
              applied_bytes=GREATEST(applied_bytes,VALUES(applied_bytes)),status=VALUES(status)
            """)
    void upsertFullProgress(io.github.redisops.sync.worker.domain.WorkerSyncFullProgress progress);

    class PrecheckRow {
        public Long id;
        public long taskId;
        public String status, reportJson;
        public java.time.Instant checkedAt, validUntil;

        static PrecheckRow from(WorkerSyncPrecheckReport report) {
            PrecheckRow row = new PrecheckRow();
            row.taskId = report.taskId();
            row.status = report.status();
            row.reportJson = report.reportJson();
            row.checkedAt = report.checkedAt();
            row.validUntil = report.validUntil();
            return row;
        }
    }
}
