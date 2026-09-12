package io.github.redisops.domain.governance;

import java.util.List;
import java.util.Optional;

public interface CleanupGovernanceRepository {
    CleanupGovernanceTask saveTask(CleanupGovernanceTask task);
    Optional<CleanupGovernanceTask> findTask(long id);
    List<CleanupGovernanceTask> findTasks();
    boolean updateTask(CleanupGovernanceTask task, long version);
    CleanupGovernanceRun saveRun(CleanupGovernanceRun run);
    Optional<CleanupGovernanceRun> latestRun(long taskId);
    CleanupGovernanceCheckpoint saveCheckpoint(CleanupGovernanceCheckpoint checkpoint);
    Optional<CleanupGovernanceCheckpoint> checkpoint(long runId, String shardId);
    List<CleanupGovernanceCheckpoint> checkpoints(long runId);
}
