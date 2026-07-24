package io.github.redisops.domain.sync;
import java.util.*;
public interface SyncRepository {
    SyncTask saveTask(SyncTask task, String operator, String message);
    Optional<SyncTask> findTask(long id);
    List<SyncTask> findTasks(Long relationId);
    Optional<SyncTask> findLatestTask(long relationId);
    boolean updateTask(SyncTask task, long version, String operator, String message);
    List<SyncTaskEvent> findEvents(long taskId);
    long countActiveTasks(long relationId);
    Switchover saveSwitchover(Switchover switchover);
    Optional<Switchover> findSwitchover(long id);
    List<Switchover> findSwitchovers(long relationId);
    Optional<Switchover> findActiveSwitchoverByTask(long taskId);
    boolean updateSwitchover(Switchover switchover, long version);
    long countActiveSwitchovers(long relationId);
    Optional<SyncRuntime> findRuntime(long taskId);
    boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds);
    boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes);
    void releaseRuntime(long taskId, String owner, String phase, String error);
    List<SyncChannelCheckpoint> findChannels(long taskId);
    void upsertChannel(SyncChannelCheckpoint checkpoint);
    Optional<SyncPrecheckReport> findLatestPrecheck(long taskId);
    SyncPrecheckReport savePrecheck(SyncPrecheckReport report);
    List<SyncMetricSnapshot> findMetrics(long taskId, int limit);
    void saveMetric(SyncMetricSnapshot metric);
}
