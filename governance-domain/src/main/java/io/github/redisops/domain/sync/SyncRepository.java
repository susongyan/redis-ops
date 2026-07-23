package io.github.redisops.domain.sync;
import java.util.*;
public interface SyncRepository {
    SyncTask saveTask(SyncTask task,String operator,String message); Optional<SyncTask> findTask(long id); List<SyncTask> findTasks(Long relationId);
    Optional<SyncTask> findLatestTask(long relationId); boolean updateTask(SyncTask task,long version,String operator,String message);
    List<SyncTaskEvent> findEvents(long taskId); long countActiveTasks(long relationId);
    Switchover saveSwitchover(Switchover switchover); Optional<Switchover> findSwitchover(long id); List<Switchover> findSwitchovers(long relationId);
    boolean updateSwitchover(Switchover switchover,long version); long countActiveSwitchovers(long relationId);
}
