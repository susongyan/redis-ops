package io.github.redisops.infrastructure.persistence;

import io.github.redisops.common.PageResult;
import io.github.redisops.domain.validation.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisValidationRepository implements ValidationRepository {
    private final ValidationMapper mapper;
    public MyBatisValidationRepository(ValidationMapper mapper) {
        this.mapper = mapper;
    }
    public ValidationTask saveTask(ValidationTask task) {
        var row = ValidationMapper.TaskRow.from(task);
        mapper.insertTask(row);
        return mapper.findTask(row.id);
    }
    public Optional<ValidationTask> findTask(long id) {
        return Optional.ofNullable(mapper.findTask(id));
    }
    public List<ValidationTask> findTasks() {
        return mapper.findTasks();
    }
    public boolean updateTask(ValidationTask task, long expectedVersion) {
        return mapper.updateTask(ValidationMapper.TaskRow.from(task), expectedVersion) == 1;
    }
    public ValidationRun saveRun(ValidationRun run) {
        var row = ValidationMapper.RunRow.from(run);
        mapper.insertRun(row);
        return run.id() == null ? mapper.findLatestRun(run.taskId()) : run;
    }
    public Optional<ValidationRun> findLatestRun(long taskId) {
        return Optional.ofNullable(mapper.findLatestRun(taskId));
    }
    public PageResult<ValidationDifference> findDifferences(long runId, int page, int size) {
        int safePage = Math.max(1, page), safeSize = Math.max(1, Math.min(100, size));
        return new PageResult<>(mapper.findDifferences(runId, (safePage - 1) * safeSize, safeSize),
                mapper.countDifferences(runId), safePage, safeSize);
    }
    public void saveDifferences(List<ValidationDifference> differences) {
        differences.forEach(x -> mapper.insertDifference(ValidationMapper.DifferenceRow.from(x)));
    }
}
