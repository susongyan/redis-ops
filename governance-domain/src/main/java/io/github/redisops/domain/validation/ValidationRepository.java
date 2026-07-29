package io.github.redisops.domain.validation;

import io.github.redisops.common.PageResult;
import java.util.*;

public interface ValidationRepository {
    ValidationTask saveTask(ValidationTask task);
    Optional<ValidationTask> findTask(long id);
    List<ValidationTask> findTasks();
    boolean updateTask(ValidationTask task, long expectedVersion);
    ValidationRun saveRun(ValidationRun run);
    Optional<ValidationRun> findLatestRun(long taskId);
    PageResult<ValidationDifference> findDifferences(long runId, int page, int size);
    void saveDifferences(List<ValidationDifference> differences);
}
