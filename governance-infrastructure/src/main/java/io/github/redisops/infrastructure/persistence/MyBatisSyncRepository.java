package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.sync.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class MyBatisSyncRepository implements SyncRepository {
    private final SyncMapper mapper;
    public MyBatisSyncRepository(SyncMapper mapper) {
        this.mapper = mapper;
    }
    public SyncTask saveTask(SyncTask x, String op, String message) {
        var r = SyncMapper.TaskRow.from(x);
        mapper.insertTask(r);
        event(r.id, null, x.status(), op, message);
        return mapper.findTask(r.id);
    }
    public Optional<SyncTask> findTask(long id) {
        return Optional.ofNullable(mapper.findTask(id));
    }
    public List<SyncTask> findTasks(Long relationId) {
        return mapper.findTasks(relationId);
    }
    public Optional<SyncTask> findLatestTask(long relationId) {
        return Optional.ofNullable(mapper.findLatestTask(relationId));
    }
    public boolean updateTask(SyncTask x, long version, String op, String message) {
        var before = mapper.findTask(x.id());
        if (before == null || mapper.updateTask(SyncMapper.TaskRow.from(x), version) != 1)
            return false;
        event(x.id(), before.status(), x.status(), op, message);
        return true;
    }
    public List<SyncTaskEvent> findEvents(long id, int offset, int limit) {
        return mapper.findEvents(id, offset, limit);
    }
    public long countEvents(long id) {
        return mapper.countEvents(id);
    }
    public long countActiveTasks(long id) {
        return mapper.countActiveTasks(id);
    }
    public Switchover saveSwitchover(Switchover x) {
        var r = SyncMapper.SwitchoverRow.from(x);
        mapper.insertSwitchover(r);
        return mapper.findSwitchover(r.id);
    }
    public Optional<Switchover> findSwitchover(long id) {
        return Optional.ofNullable(mapper.findSwitchover(id));
    }
    public List<Switchover> findSwitchovers(long id) {
        return mapper.findSwitchovers(id);
    }
    public Optional<Switchover> findActiveSwitchoverByTask(long taskId) {
        return Optional.ofNullable(mapper.findActiveSwitchoverByTask(taskId));
    }
    public boolean updateSwitchover(Switchover x, long v) {
        return mapper.updateSwitchover(SyncMapper.SwitchoverRow.from(x), v) == 1;
    }
    public long countActiveSwitchovers(long id) {
        return mapper.countActiveSwitchovers(id);
    }
    public Optional<SyncRuntime> findRuntime(long taskId) {
        return Optional.ofNullable(mapper.findRuntime(taskId));
    }
    public boolean claimRuntime(long taskId, String runtimeId, String owner, long leaseSeconds) {
        mapper.ensureRuntime(taskId, runtimeId);
        return mapper.claimRuntime(taskId, runtimeId, owner, leaseSeconds) == 1;
    }
    public boolean renewRuntime(long taskId, String owner, long leaseSeconds, String phase, long spoolBytes) {
        return mapper.renewRuntime(taskId, owner, leaseSeconds, phase, spoolBytes) == 1;
    }
    public void releaseRuntime(long taskId, String owner, String phase, String error) {
        mapper.releaseRuntime(taskId, owner, phase, error);
    }
    public void updateRuntimeObservation(long taskId, String owner, String phase, Long targetFenceGeneration,
            java.time.Instant fencePublishedAt, String recoveryAction, String error) {
        mapper.updateRuntimeObservation(taskId, owner, phase, targetFenceGeneration, fencePublishedAt,
                recoveryAction, error);
    }
    public List<SyncTask> findExpiredRecoverableTasks(int limit) {
        return mapper.findExpiredRecoverableTasks(Math.max(1, Math.min(limit, 100)));
    }
    public void appendTaskEvent(long taskId, String operator, String message) {
        SyncTask task = mapper.findTask(taskId);
        if (task == null)
            return;
        event(taskId, task.status(), task.status(), operator, message);
    }
    public List<SyncChannelCheckpoint> findChannels(long taskId) {
        return mapper.findChannels(taskId);
    }
    public void upsertChannel(SyncChannelCheckpoint checkpoint) {
        mapper.upsertChannel(checkpoint);
    }
    public Optional<SyncPrecheckReport> findLatestPrecheck(long taskId) {
        return Optional.ofNullable(mapper.findLatestPrecheck(taskId));
    }
    public SyncPrecheckReport savePrecheck(SyncPrecheckReport report) {
        var row = SyncMapper.SyncPrecheckReportRow.from(report);
        mapper.insertPrecheck(row);
        return new SyncPrecheckReport(row.id, row.taskId, row.status, row.reportJson, row.checkedAt, row.validUntil);
    }
    public List<SyncMetricSnapshot> findMetrics(long taskId, int limit) {
        return mapper.findMetrics(taskId, Math.max(1, Math.min(limit, 1000)));
    }
    public void saveMetric(SyncMetricSnapshot metric) {
        mapper.insertMetric(metric);
    }
    public List<SyncFullProgress> findFullProgress(long taskId) {
        return mapper.findFullProgress(taskId);
    }
    public void upsertFullProgress(SyncFullProgress progress) {
        mapper.upsertFullProgress(progress);
    }
    private void event(long id, SyncTaskStatus from, SyncTaskStatus to, String op, String message) {
        var r = new SyncMapper.EventRow();
        r.taskId = id;
        r.fromStatus = from == null ? null : from.name();
        r.toStatus = to.name();
        r.operator = op;
        r.message = message;
        mapper.insertEvent(r);
    }
}
