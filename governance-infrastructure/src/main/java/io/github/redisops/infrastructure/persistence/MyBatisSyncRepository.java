package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.sync.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class MyBatisSyncRepository implements SyncRepository {
    private final SyncMapper mapper;public MyBatisSyncRepository(SyncMapper mapper){this.mapper=mapper;}
    public SyncTask saveTask(SyncTask x,String op,String message){var r=SyncMapper.TaskRow.from(x);mapper.insertTask(r);event(r.id,null,x.status(),op,message);return mapper.findTask(r.id);}
    public Optional<SyncTask> findTask(long id){return Optional.ofNullable(mapper.findTask(id));}public List<SyncTask> findTasks(Long relationId){return mapper.findTasks(relationId);}public Optional<SyncTask> findLatestTask(long relationId){return Optional.ofNullable(mapper.findLatestTask(relationId));}
    public boolean updateTask(SyncTask x,long version,String op,String message){var before=mapper.findTask(x.id());if(before==null||mapper.updateTask(SyncMapper.TaskRow.from(x),version)!=1)return false;event(x.id(),before.status(),x.status(),op,message);return true;}
    public List<SyncTaskEvent> findEvents(long id){return mapper.findEvents(id);}public long countActiveTasks(long id){return mapper.countActiveTasks(id);}
    public Switchover saveSwitchover(Switchover x){var r=SyncMapper.SwitchoverRow.from(x);mapper.insertSwitchover(r);return mapper.findSwitchover(r.id);}
    public Optional<Switchover> findSwitchover(long id){return Optional.ofNullable(mapper.findSwitchover(id));}public List<Switchover> findSwitchovers(long id){return mapper.findSwitchovers(id);}
    public boolean updateSwitchover(Switchover x,long v){return mapper.updateSwitchover(SyncMapper.SwitchoverRow.from(x),v)==1;}public long countActiveSwitchovers(long id){return mapper.countActiveSwitchovers(id);}
    private void event(long id,SyncTaskStatus from,SyncTaskStatus to,String op,String message){var r=new SyncMapper.EventRow();r.taskId=id;r.fromStatus=from==null?null:from.name();r.toStatus=to.name();r.operator=op;r.message=message;mapper.insertEvent(r);}
}
