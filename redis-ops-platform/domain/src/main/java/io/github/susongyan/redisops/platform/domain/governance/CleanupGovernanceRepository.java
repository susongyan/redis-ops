package io.github.susongyan.redisops.platform.domain.governance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CleanupGovernanceRepository {
    CleanupGovernanceTask saveTask(CleanupGovernanceTask task);
    Optional<CleanupGovernanceTask> findTask(long id);
    List<CleanupGovernanceTask> findTasks();
    Optional<CleanupGovernanceTask> findActiveTaskForCluster(long clusterId);
    List<CleanupGovernanceTask> findStaleQueuedTasks(int minutes);
    boolean updateTask(CleanupGovernanceTask task, long version);
    CleanupGovernanceRun saveRun(CleanupGovernanceRun run);
    Optional<CleanupGovernanceRun> latestRun(long taskId);
    /** Newest-first run history (bounded): the drawer keeps the preflight result next to the apply run. */
    List<CleanupGovernanceRun> findRuns(long taskId);
    /** Run still writing within the threshold: the previous execution thread is exiting. */
    Optional<CleanupGovernanceRun> findRecentlyActiveRun(long taskId, int seconds);
    List<CleanupGovernanceRun> findStaleRuns(int minutes);
    boolean failRun(long id, Instant completedAt, String errorCode);
    void touchRun(long id);
    CleanupGovernanceCheckpoint saveCheckpoint(CleanupGovernanceCheckpoint checkpoint);
    Optional<CleanupGovernanceCheckpoint> checkpoint(long runId, String shardId);
    List<CleanupGovernanceCheckpoint> checkpoints(long runId);
    /** Closes shard rows still on RUNNING when the run reaches a terminal state. */
    void finalizeCheckpoints(long runId, TtlGovernanceStatus status);
}
