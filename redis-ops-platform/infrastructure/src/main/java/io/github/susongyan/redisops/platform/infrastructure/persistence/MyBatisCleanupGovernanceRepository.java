package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.governance.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCleanupGovernanceRepository implements CleanupGovernanceRepository {
    private final CleanupGovernanceMapper mapper;
    public MyBatisCleanupGovernanceRepository(CleanupGovernanceMapper mapper) {
        this.mapper = mapper;
    }
    public CleanupGovernanceTask saveTask(CleanupGovernanceTask x) {
        var r = new CleanupGovernanceMapper.TaskRow();
        r.taskNo = x.taskNo();
        r.clusterId = x.clusterId();
        r.databaseNo = x.databaseNo();
        r.includePattern = x.includePattern();
        r.impactLimit = x.impactLimit();
        r.scanRatePerSecond = x.scanRatePerSecond();
        r.status = x.status().name();
        r.approvalStatus = x.approvalStatus().name();
        r.approvalNote = x.approvalNote();
        mapper.insertTask(r);
        return mapper.task(r.id);
    }
    public Optional<CleanupGovernanceTask> findTask(long id) {
        return Optional.ofNullable(mapper.task(id));
    }
    public List<CleanupGovernanceTask> findTasks() {
        return mapper.tasks();
    }
    public Optional<CleanupGovernanceTask> findActiveTaskForCluster(long clusterId) {
        return Optional.ofNullable(mapper.findActiveTaskForCluster(clusterId));
    }
    public List<CleanupGovernanceTask> findStaleQueuedTasks(int minutes) {
        return mapper.findStaleQueuedTasks(minutes);
    }
    public boolean updateTask(CleanupGovernanceTask x, long version) {
        var r = new CleanupGovernanceMapper.TaskRow();
        r.id = x.id();
        r.status = x.status().name();
        r.approvalStatus = x.approvalStatus().name();
        r.approvalNote = x.approvalNote();
        r.version = version;
        return mapper.updateTask(r) == 1;
    }
    public CleanupGovernanceRun saveRun(CleanupGovernanceRun x) {
        var r = new CleanupGovernanceMapper.RunRow();
        r.id = x.id();
        r.taskId = x.taskId();
        r.runNo = x.runNo();
        r.status = x.status().name();
        r.plannedKeys = x.plannedKeys();
        r.scannedKeys = x.scannedKeys();
        r.candidateKeys = x.candidateKeys();
        r.deletedKeys = x.deletedKeys();
        r.skippedKeys = x.skippedKeys();
        r.failedKeys = x.failedKeys();
        r.startedAt = x.startedAt();
        r.completedAt = x.completedAt();
        r.errorCode = x.errorCode();
        if (x.id() == null)
            mapper.insertRun(r);
        else
            mapper.updateRun(r);
        return mapper.latestRun(x.taskId());
    }
    public Optional<CleanupGovernanceRun> latestRun(long id) {
        return Optional.ofNullable(mapper.latestRun(id));
    }
    public List<CleanupGovernanceRun> findRuns(long taskId) {
        return mapper.findRuns(taskId);
    }
    public Optional<CleanupGovernanceRun> findRecentlyActiveRun(long taskId, int seconds) {
        return Optional.ofNullable(mapper.findRecentlyActiveRun(taskId, seconds));
    }
    public List<CleanupGovernanceRun> findStaleRuns(int minutes) {
        return mapper.findStaleRuns(minutes);
    }
    public boolean failRun(long id, java.time.Instant completedAt, String errorCode) {
        return mapper.failRun(id, completedAt, errorCode) == 1;
    }
    public void touchRun(long id) {
        mapper.touchRun(id);
    }
    public CleanupGovernanceCheckpoint saveCheckpoint(CleanupGovernanceCheckpoint x) {
        var r = new CleanupGovernanceMapper.CheckpointRow();
        r.runId = x.runId();
        r.shardId = x.shardId();
        r.cursor = x.cursor();
        r.scannedKeys = x.scannedKeys();
        r.status = x.status().name();
        mapper.upsertCheckpoint(r);
        return mapper.checkpoint(x.runId(), x.shardId());
    }
    public Optional<CleanupGovernanceCheckpoint> checkpoint(long id, String shard) {
        return Optional.ofNullable(mapper.checkpoint(id, shard));
    }
    public List<CleanupGovernanceCheckpoint> checkpoints(long id) {
        return mapper.checkpoints(id);
    }
    public void finalizeCheckpoints(long runId, TtlGovernanceStatus status) {
        mapper.finalizeCheckpoints(runId, status.name());
    }
}
