package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.sync.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SyncMapper {
    String TASK_COLUMNS = "id,task_no,relation_id,source_cluster_id,target_cluster_id,purpose,sync_mode,status,tool_type,"
            +
            "source_db,target_db,CAST(include_patterns_json AS CHAR) include_patterns_json," +
            "CAST(exclude_patterns_json AS CHAR) exclude_patterns_json,rate_limit_ops,bandwidth_limit_bytes_per_second,"
            +
            "spool_limit_bytes,desired_action,write_fenced,write_fence_note,blocked_reason,full_sync_epoch," +
            "last_rpo_seconds,last_error,version,created_at,updated_at,finished_at";
    String SWITCH_COLUMNS = "id,relation_id,old_primary_cluster_id,old_standby_cluster_id,stopped_task_id,reverse_task_id,status,operator_id AS operator,source_write_fenced,source_fence_note,last_error,version,created_at,updated_at,confirmed_at";
    @Insert("""
            INSERT INTO sync_task(task_no,relation_id,source_cluster_id,target_cluster_id,purpose,sync_mode,status,
              tool_type,source_db,target_db,include_patterns_json,exclude_patterns_json,rate_limit_ops,
              bandwidth_limit_bytes_per_second,spool_limit_bytes,desired_action,write_fenced,write_fence_note,
              blocked_reason,full_sync_epoch,last_rpo_seconds,last_error,finished_at)
            VALUES(#{taskNo},#{relationId},#{sourceClusterId},#{targetClusterId},#{purpose},#{syncMode},#{status},
              #{toolType},#{sourceDb},#{targetDb},CAST(#{includePatternsJson} AS JSON),CAST(#{excludePatternsJson} AS JSON),
              #{rateLimitOps},#{bandwidthLimitBytesPerSecond},#{spoolLimitBytes},#{desiredAction},#{writeFenced},
              #{writeFenceNote},#{blockedReason},#{fullSyncEpoch},#{lastRpoSeconds},#{lastError},#{finishedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTask(TaskRow row);
    @Select("SELECT " + TASK_COLUMNS + " FROM sync_task WHERE id=#{id}")
    SyncTask findTask(long id);
    @Select("<script>SELECT " + TASK_COLUMNS
            + " FROM sync_task <if test='relationId != null'>WHERE relation_id=#{relationId}</if> ORDER BY id DESC</script>")
    List<SyncTask> findTasks(Long relationId);
    @Select("SELECT " + TASK_COLUMNS + " FROM sync_task WHERE relation_id=#{relationId} ORDER BY id DESC LIMIT 1")
    SyncTask findLatestTask(long relationId);
    @Update("""
            UPDATE sync_task SET status=#{row.status},desired_action=#{row.desiredAction},write_fenced=#{row.writeFenced},
              write_fence_note=#{row.writeFenceNote},blocked_reason=#{row.blockedReason},full_sync_epoch=#{row.fullSyncEpoch},
              rate_limit_ops=#{row.rateLimitOps},bandwidth_limit_bytes_per_second=#{row.bandwidthLimitBytesPerSecond},
              spool_limit_bytes=#{row.spoolLimitBytes},last_rpo_seconds=#{row.lastRpoSeconds},last_error=#{row.lastError},
              finished_at=#{row.finishedAt},version=version+1
            WHERE id=#{row.id} AND version=#{version}
            """)
    int updateTask(@Param("row") TaskRow row, @Param("version") long version);
    @Insert("INSERT INTO sync_task_event(task_id,from_status,to_status,operator_id,message) VALUES(#{taskId},#{fromStatus},#{toStatus},#{operator},#{message})")
    void insertEvent(EventRow row);
    @Select("SELECT id,task_id,from_status,to_status,operator_id AS operator,message,created_at FROM sync_task_event WHERE task_id=#{taskId} ORDER BY id")
    List<SyncTaskEvent> findEvents(long taskId);
    @Select("SELECT COUNT(*) FROM sync_task WHERE relation_id=#{relationId} AND status NOT IN ('FINISHED','CANCELLED','FAILED')")
    long countActiveTasks(long relationId);
    @Insert("INSERT INTO switchover(relation_id,old_primary_cluster_id,old_standby_cluster_id,stopped_task_id,reverse_task_id,status,operator_id,source_write_fenced,source_fence_note,last_error,confirmed_at) VALUES(#{relationId},#{oldPrimaryClusterId},#{oldStandbyClusterId},#{stoppedTaskId},#{reverseTaskId},#{status},#{operator},#{sourceWriteFenced},#{sourceFenceNote},#{lastError},#{confirmedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertSwitchover(SwitchoverRow row);
    @Select("SELECT " + SWITCH_COLUMNS + " FROM switchover WHERE id=#{id}")
    Switchover findSwitchover(long id);
    @Select("SELECT " + SWITCH_COLUMNS + " FROM switchover WHERE relation_id=#{relationId} ORDER BY id DESC")
    List<Switchover> findSwitchovers(long relationId);
    @Select("SELECT " + SWITCH_COLUMNS
            + " FROM switchover WHERE stopped_task_id=#{taskId} AND status IN ('WAITING_SOURCE_FENCE','DRAINING','WAITING_EXTERNAL_SWITCH') ORDER BY id DESC LIMIT 1")
    Switchover findActiveSwitchoverByTask(long taskId);
    @Update("UPDATE switchover SET reverse_task_id=#{row.reverseTaskId},status=#{row.status},source_write_fenced=#{row.sourceWriteFenced},source_fence_note=#{row.sourceFenceNote},last_error=#{row.lastError},confirmed_at=#{row.confirmedAt},version=version+1 WHERE id=#{row.id} AND version=#{version}")
    int updateSwitchover(@Param("row") SwitchoverRow row, @Param("version") long version);
    @Select("SELECT COUNT(*) FROM switchover WHERE relation_id=#{relationId} AND status IN ('WAITING_SOURCE_FENCE','DRAINING','WAITING_EXTERNAL_SWITCH')")
    long countActiveSwitchovers(long relationId);
    @Insert("""
            INSERT IGNORE INTO sync_runtime(task_id,runtime_id,phase)
            VALUES(#{taskId},#{runtimeId},'IDLE')
            """)
    void ensureRuntime(@Param("taskId") long taskId, @Param("runtimeId") String runtimeId);
    @Update("""
            UPDATE sync_runtime SET runtime_id=#{runtimeId},lease_owner=#{owner},
              lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL #{leaseSeconds} SECOND),
              fencing_generation=fencing_generation+1,phase='CLAIMED',
              heartbeat_at=CURRENT_TIMESTAMP(3),started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3))
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
    @Select("""
            SELECT task_id,runtime_id,lease_owner,lease_until,fencing_generation,phase,heartbeat_at,
              spool_bytes,recovery_action,last_error,started_at,updated_at
            FROM sync_runtime WHERE task_id=#{taskId}
            """)
    SyncRuntime findRuntime(long taskId);
    @Select("""
            SELECT id,task_id,channel_id,source_node_id,slot_ranges,replication_id,received_offset,applied_offset,
              status,last_heartbeat_at,updated_at FROM sync_channel_checkpoint WHERE task_id=#{taskId} ORDER BY id
            """)
    List<SyncChannelCheckpoint> findChannels(long taskId);
    @Insert("""
            INSERT INTO sync_channel_checkpoint(task_id,channel_id,source_node_id,slot_ranges,replication_id,
              received_offset,applied_offset,status,last_heartbeat_at)
            VALUES(#{taskId},#{channelId},#{sourceNodeId},#{slotRanges},#{replicationId},#{receivedOffset},
              #{appliedOffset},#{status},#{lastHeartbeatAt})
            ON DUPLICATE KEY UPDATE source_node_id=VALUES(source_node_id),slot_ranges=VALUES(slot_ranges),
              replication_id=VALUES(replication_id),received_offset=VALUES(received_offset),
              applied_offset=VALUES(applied_offset),status=VALUES(status),last_heartbeat_at=VALUES(last_heartbeat_at)
            """)
    void upsertChannel(SyncChannelCheckpoint checkpoint);
    @Select("""
            SELECT id,task_id,status,CAST(report_json AS CHAR) report_json,checked_at,valid_until
            FROM sync_precheck_report WHERE task_id=#{taskId} ORDER BY checked_at DESC LIMIT 1
            """)
    SyncPrecheckReport findLatestPrecheck(long taskId);
    @Insert("""
            INSERT INTO sync_precheck_report(task_id,status,report_json,checked_at,valid_until)
            VALUES(#{taskId},#{status},CAST(#{reportJson} AS JSON),#{checkedAt},#{validUntil})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertPrecheck(SyncPrecheckReportRow row);
    @Select("""
            SELECT id,task_id,channel_id,timestamp_lag_seconds,estimated_lag_seconds,offset_gap_bytes,
              backlog_bytes,source_bytes_per_second,target_apply_bytes_per_second,catch_up_eta_seconds,
              calculation_method,confidence,collected_at
            FROM sync_metric_snapshot WHERE task_id=#{taskId} ORDER BY collected_at DESC LIMIT #{limit}
            """)
    List<SyncMetricSnapshot> findMetrics(@Param("taskId") long taskId, @Param("limit") int limit);
    @Insert("""
            INSERT INTO sync_metric_snapshot(task_id,channel_id,timestamp_lag_seconds,estimated_lag_seconds,
              offset_gap_bytes,backlog_bytes,source_bytes_per_second,target_apply_bytes_per_second,
              catch_up_eta_seconds,calculation_method,confidence,collected_at)
            VALUES(#{taskId},#{channelId},#{timestampLagSeconds},#{estimatedLagSeconds},#{offsetGapBytes},
              #{backlogBytes},#{sourceBytesPerSecond},#{targetApplyBytesPerSecond},#{catchUpEtaSeconds},
              #{calculationMethod},#{confidence},#{collectedAt})
            """)
    void insertMetric(SyncMetricSnapshot metric);
    class TaskRow {
        public Long id, relationId, lastRpoSeconds;
        public long sourceClusterId, targetClusterId, rateLimitOps,
                bandwidthLimitBytesPerSecond, spoolLimitBytes;
        public int sourceDb, targetDb;
        public boolean writeFenced;
        public String taskNo, purpose, syncMode, status, toolType, includePatternsJson,
                excludePatternsJson, desiredAction, writeFenceNote, blockedReason, fullSyncEpoch, lastError;
        public java.time.Instant finishedAt;
        static TaskRow from(SyncTask x) {
            var r = new TaskRow();
            r.id = x.id();
            r.taskNo = x.taskNo();
            r.relationId = x.relationId();
            r.sourceClusterId = x.sourceClusterId();
            r.targetClusterId = x.targetClusterId();
            r.purpose = x.purpose().name();
            r.syncMode = x.syncMode().name();
            r.status = x.status().name();
            r.toolType = x.toolType();
            r.sourceDb = x.sourceDb();
            r.targetDb = x.targetDb();
            r.includePatternsJson = x.includePatternsJson();
            r.excludePatternsJson = x.excludePatternsJson();
            r.rateLimitOps = x.rateLimitOps();
            r.bandwidthLimitBytesPerSecond = x.bandwidthLimitBytesPerSecond();
            r.spoolLimitBytes = x.spoolLimitBytes();
            r.desiredAction = x.desiredAction();
            r.writeFenced = x.writeFenced();
            r.writeFenceNote = x.writeFenceNote();
            r.blockedReason = x.blockedReason();
            r.fullSyncEpoch = x.fullSyncEpoch();
            r.lastRpoSeconds = x.lastRpoSeconds();
            r.lastError = x.lastError();
            r.finishedAt = x.finishedAt();
            return r;
        }
    }
    class EventRow {
        public long taskId;
        public String fromStatus, toStatus, operator, message;
    }
    class SwitchoverRow {
        public Long id, reverseTaskId;
        public long relationId, oldPrimaryClusterId, oldStandbyClusterId, stoppedTaskId;
        public boolean sourceWriteFenced;
        public String status, operator, sourceFenceNote, lastError;
        public java.time.Instant confirmedAt;
        static SwitchoverRow from(Switchover x) {
            var r = new SwitchoverRow();
            r.id = x.id();
            r.relationId = x.relationId();
            r.oldPrimaryClusterId = x.oldPrimaryClusterId();
            r.oldStandbyClusterId = x.oldStandbyClusterId();
            r.stoppedTaskId = x.stoppedTaskId();
            r.reverseTaskId = x.reverseTaskId();
            r.status = x.status().name();
            r.operator = x.operator();
            r.sourceWriteFenced = x.sourceWriteFenced();
            r.sourceFenceNote = x.sourceFenceNote();
            r.lastError = x.lastError();
            r.confirmedAt = x.confirmedAt();
            return r;
        }
    }
    class SyncPrecheckReportRow {
        public Long id;
        public long taskId;
        public String status, reportJson;
        public java.time.Instant checkedAt, validUntil;
        static SyncPrecheckReportRow from(SyncPrecheckReport x) {
            var r = new SyncPrecheckReportRow();
            r.id = x.id();
            r.taskId = x.taskId();
            r.status = x.status();
            r.reportJson = x.reportJson();
            r.checkedAt = x.checkedAt();
            r.validUntil = x.validUntil();
            return r;
        }
    }
}
