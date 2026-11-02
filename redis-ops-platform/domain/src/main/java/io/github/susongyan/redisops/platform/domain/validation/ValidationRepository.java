package io.github.susongyan.redisops.platform.domain.validation;

import io.github.susongyan.redisops.platform.common.PageResult;
import java.util.*;

public interface ValidationRepository {
    ValidationTask saveTask(ValidationTask task);
    Optional<ValidationTask> findTask(long id);
    List<ValidationTask> findTasks();
    Optional<ValidationTask> findActiveTaskForPair(long sourceClusterId, long targetClusterId);
    boolean updateTask(ValidationTask task, long expectedVersion);
    ValidationRun saveRun(ValidationRun run);
    Optional<ValidationRun> findLatestRun(long taskId);
    PageResult<ValidationDifference> findDifferences(long runId, int page, int size);
    void touchRun(long runId);
    boolean failRun(long runId);
    List<ValidationTask> findStaleQueuedTasks(int staleMinutes);
    List<ValidationRun> findStaleRuns(java.time.Instant threshold);
    void saveDifferences(List<ValidationDifference> differences);
}
