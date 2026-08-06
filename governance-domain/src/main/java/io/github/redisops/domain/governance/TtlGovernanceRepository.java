package io.github.redisops.domain.governance;

import java.util.List;
import java.util.Optional;

public interface TtlGovernanceRepository {
    TtlGovernanceTask saveTask(TtlGovernanceTask task);
    Optional<TtlGovernanceTask> findTask(long id);
    List<TtlGovernanceTask> findTasks();
    boolean updateTask(TtlGovernanceTask task, long version);
    TtlGovernanceRun saveRun(TtlGovernanceRun run);
    Optional<TtlGovernanceRun> latestRun(long taskId);
    TtlGovernanceCheckpoint saveCheckpoint(TtlGovernanceCheckpoint checkpoint);
    Optional<TtlGovernanceCheckpoint> checkpoint(long runId, String shardId);
    List<TtlGovernanceCheckpoint> checkpoints(long runId);
}
