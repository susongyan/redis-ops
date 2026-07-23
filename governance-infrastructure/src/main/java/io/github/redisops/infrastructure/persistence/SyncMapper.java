package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.sync.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SyncMapper {
    String TASK_COLUMNS="id,task_no,relation_id,source_cluster_id,target_cluster_id,purpose,sync_mode,status,tool_type,last_rpo_seconds,last_error,version,created_at,updated_at,finished_at";
    String SWITCH_COLUMNS="id,relation_id,old_primary_cluster_id,old_standby_cluster_id,stopped_task_id,reverse_task_id,status,operator_id AS operator,last_error,version,created_at,updated_at,confirmed_at";
    @Insert("INSERT INTO sync_task(task_no,relation_id,source_cluster_id,target_cluster_id,purpose,sync_mode,status,tool_type,last_rpo_seconds,last_error,finished_at) VALUES(#{taskNo},#{relationId},#{sourceClusterId},#{targetClusterId},#{purpose},#{syncMode},#{status},#{toolType},#{lastRpoSeconds},#{lastError},#{finishedAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertTask(TaskRow row);
    @Select("SELECT "+TASK_COLUMNS+" FROM sync_task WHERE id=#{id}") SyncTask findTask(long id);
    @Select("<script>SELECT "+TASK_COLUMNS+" FROM sync_task <if test='relationId != null'>WHERE relation_id=#{relationId}</if> ORDER BY id DESC</script>") List<SyncTask> findTasks(Long relationId);
    @Select("SELECT "+TASK_COLUMNS+" FROM sync_task WHERE relation_id=#{relationId} ORDER BY id DESC LIMIT 1") SyncTask findLatestTask(long relationId);
    @Update("UPDATE sync_task SET status=#{row.status},last_rpo_seconds=#{row.lastRpoSeconds},last_error=#{row.lastError},finished_at=#{row.finishedAt},version=version+1 WHERE id=#{row.id} AND version=#{version}") int updateTask(@Param("row")TaskRow row,@Param("version")long version);
    @Insert("INSERT INTO sync_task_event(task_id,from_status,to_status,operator_id,message) VALUES(#{taskId},#{fromStatus},#{toStatus},#{operator},#{message})") void insertEvent(EventRow row);
    @Select("SELECT id,task_id,from_status,to_status,operator_id AS operator,message,created_at FROM sync_task_event WHERE task_id=#{taskId} ORDER BY id") List<SyncTaskEvent> findEvents(long taskId);
    @Select("SELECT COUNT(*) FROM sync_task WHERE relation_id=#{relationId} AND status NOT IN ('FINISHED','CANCELLED','FAILED')") long countActiveTasks(long relationId);
    @Insert("INSERT INTO switchover(relation_id,old_primary_cluster_id,old_standby_cluster_id,stopped_task_id,reverse_task_id,status,operator_id,last_error,confirmed_at) VALUES(#{relationId},#{oldPrimaryClusterId},#{oldStandbyClusterId},#{stoppedTaskId},#{reverseTaskId},#{status},#{operator},#{lastError},#{confirmedAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insertSwitchover(SwitchoverRow row);
    @Select("SELECT "+SWITCH_COLUMNS+" FROM switchover WHERE id=#{id}") Switchover findSwitchover(long id);
    @Select("SELECT "+SWITCH_COLUMNS+" FROM switchover WHERE relation_id=#{relationId} ORDER BY id DESC") List<Switchover> findSwitchovers(long relationId);
    @Update("UPDATE switchover SET reverse_task_id=#{row.reverseTaskId},status=#{row.status},last_error=#{row.lastError},confirmed_at=#{row.confirmedAt},version=version+1 WHERE id=#{row.id} AND version=#{version}") int updateSwitchover(@Param("row")SwitchoverRow row,@Param("version")long version);
    @Select("SELECT COUNT(*) FROM switchover WHERE relation_id=#{relationId} AND status='WAITING_EXTERNAL_SWITCH'") long countActiveSwitchovers(long relationId);
    class TaskRow { public Long id,relationId,lastRpoSeconds;public long sourceClusterId,targetClusterId;public String taskNo,purpose,syncMode,status,toolType,lastError;public java.time.Instant finishedAt;static TaskRow from(SyncTask x){var r=new TaskRow();r.id=x.id();r.taskNo=x.taskNo();r.relationId=x.relationId();r.sourceClusterId=x.sourceClusterId();r.targetClusterId=x.targetClusterId();r.purpose=x.purpose().name();r.syncMode=x.syncMode().name();r.status=x.status().name();r.toolType=x.toolType();r.lastRpoSeconds=x.lastRpoSeconds();r.lastError=x.lastError();r.finishedAt=x.finishedAt();return r;} }
    class EventRow { public long taskId;public String fromStatus,toStatus,operator,message; }
    class SwitchoverRow { public Long id,reverseTaskId;public long relationId,oldPrimaryClusterId,oldStandbyClusterId,stoppedTaskId;public String status,operator,lastError;public java.time.Instant confirmedAt;static SwitchoverRow from(Switchover x){var r=new SwitchoverRow();r.id=x.id();r.relationId=x.relationId();r.oldPrimaryClusterId=x.oldPrimaryClusterId();r.oldStandbyClusterId=x.oldStandbyClusterId();r.stoppedTaskId=x.stoppedTaskId();r.reverseTaskId=x.reverseTaskId();r.status=x.status().name();r.operator=x.operator();r.lastError=x.lastError();r.confirmedAt=x.confirmedAt();return r;} }
}
