package io.github.susongyan.redisops.platform.domain.governance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TtlGovernanceRepository {
    TtlGovernanceTask saveTask(TtlGovernanceTask task);
    Optional<TtlGovernanceTask> findTask(long id);
    List<TtlGovernanceTask> findTasks();
    Optional<TtlGovernanceTask> findActiveTaskForCluster(long clusterId);
    List<TtlGovernanceTask> findStaleQueuedTasks(int minutes);
    boolean updateTask(TtlGovernanceTask task, long version);
    TtlGovernanceRun saveRun(TtlGovernanceRun run);
    Optional<TtlGovernanceRun> latestRun(long taskId);
    /** Newest-first run history (bounded): the drawer keeps the preflight result next to the apply run. */
    List<TtlGovernanceRun> findRuns(long taskId);
    /** Run still writing within the threshold: the previous execution thread is exiting. */
    Optional<TtlGovernanceRun> findRecentlyActiveRun(long taskId, int seconds);
    List<TtlGovernanceRun> findStaleRuns(int minutes);
    boolean failRun(long id, Instant completedAt, String errorCode);
    void touchRun(long id);
    TtlGovernanceCheckpoint saveCheckpoint(TtlGovernanceCheckpoint checkpoint);
    Optional<TtlGovernanceCheckpoint> checkpoint(long runId, String shardId);
    List<TtlGovernanceCheckpoint> checkpoints(long runId);
    /** Closes shard rows still on RUNNING when the run reaches a terminal state. */
    void finalizeCheckpoints(long runId, TtlGovernanceStatus status);
}
