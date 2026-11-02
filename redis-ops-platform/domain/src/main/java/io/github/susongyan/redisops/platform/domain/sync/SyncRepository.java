package io.github.susongyan.redisops.platform.domain.sync;
import java.util.*;
public interface SyncRepository {
    SyncTask saveTask(SyncTask task, String operator, String message);
    Optional<SyncTask> findTask(long id);
    List<SyncTask> findTasks(Long relationId);
    Optional<SyncTask> findLatestTask(long relationId);
    boolean updateTask(SyncTask task, long version, String operator, String message);
    /** Flags the task so every sync worker deletes its local spool directory on the next sweep. */
    void requestSpoolCleanup(long taskId, String operator);
    List<SyncTaskEvent> findEvents(long taskId, int offset, int limit);
    long countEvents(long taskId);
    long countActiveTasks(long relationId);
    Switchover saveSwitchover(Switchover switchover);
    Optional<Switchover> findSwitchover(long id);
    List<Switchover> findSwitchovers(long relationId);
    Optional<Switchover> findActiveSwitchoverByTask(long taskId);
    boolean updateSwitchover(Switchover switchover, long version);
    long countActiveSwitchovers(long relationId);
    Optional<SyncRuntime> findRuntime(long taskId);
    List<SyncWorkerAssignment> findWorkerAssignments(List<Long> taskIds);
    List<SyncWorkerInstance> findWorkers(long staleSeconds);
    Optional<SyncWorkerInstance> findWorker(String instanceName, long staleSeconds);
    List<SyncWorkerRuntimeRow> findWorkerRuntimes();
    int purgeOfflineWorkers(long retentionSeconds);
    boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds);
    boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes);
    void releaseRuntime(long taskId, String owner, String phase, String error);
    void updateRuntimeObservation(long taskId, String owner, String phase, Long targetFenceGeneration,
            java.time.Instant fencePublishedAt, String recoveryAction, String error);
    List<SyncTask> findExpiredRecoverableTasks(int limit);
    void appendTaskEvent(long taskId, String operator, String message);
    List<SyncChannelCheckpoint> findChannels(long taskId);
    void upsertChannel(SyncChannelCheckpoint checkpoint);
    Optional<SyncPrecheckReport> findLatestPrecheck(long taskId);
    SyncPrecheckReport savePrecheck(SyncPrecheckReport report);
    List<SyncMetricSnapshot> findMetrics(long taskId, int limit);
    void saveMetric(SyncMetricSnapshot metric);
    List<SyncFullProgress> findFullProgress(long taskId);
    void upsertFullProgress(SyncFullProgress progress);
}
