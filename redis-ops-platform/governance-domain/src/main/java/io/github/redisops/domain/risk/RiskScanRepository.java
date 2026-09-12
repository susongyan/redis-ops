package io.github.redisops.domain.risk;
import io.github.redisops.common.PageResult;
import java.util.*;
public interface RiskScanRepository {
    RiskScanTask saveTask(RiskScanTask task);
    Optional<RiskScanTask> findTask(long id);
    List<RiskScanTask> findTasks();
    boolean updateTask(RiskScanTask task, long version);
    RiskScanRun saveRun(RiskScanRun run);
    Optional<RiskScanRun> latestRun(long taskId);
    Optional<RiskScanCheckpoint> checkpoint(long runId, String shardId);
    List<RiskScanCheckpoint> checkpoints(long runId);
    void saveCheckpoint(RiskScanCheckpoint checkpoint);
    void saveFindings(List<RiskFinding> findings);
    PageResult<RiskFinding> findings(long runId, int page, int size, String riskType);
    long findingCountByType(long runId, String riskType);
}
