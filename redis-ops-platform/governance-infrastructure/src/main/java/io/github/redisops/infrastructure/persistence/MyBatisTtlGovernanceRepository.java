package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.governance.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTtlGovernanceRepository implements TtlGovernanceRepository {
    private final TtlGovernanceMapper mapper;
    public MyBatisTtlGovernanceRepository(TtlGovernanceMapper mapper) {
        this.mapper = mapper;
    }
    public TtlGovernanceTask saveTask(TtlGovernanceTask t) {
        var r = new TtlGovernanceMapper.TaskRow();
        r.taskNo = t.taskNo();
        r.clusterId = t.clusterId();
        r.databaseNo = t.databaseNo();
        r.includePattern = t.includePattern();
        r.targetTtlSeconds = t.targetTtlSeconds();
        r.scanRatePerSecond = t.scanRatePerSecond();
        r.maxKeys = t.maxKeys();
        r.status = t.status().name();
        r.approvalStatus = t.approvalStatus().name();
        mapper.insertTask(r);
        return mapper.task(r.id);
    }
    public Optional<TtlGovernanceTask> findTask(long id) {
        return Optional.ofNullable(mapper.task(id));
    }
    public List<TtlGovernanceTask> findTasks() {
        return mapper.tasks();
    }
    public boolean updateTask(TtlGovernanceTask t, long version) {
        var r = new TtlGovernanceMapper.TaskRow();
        r.id = t.id();
        r.status = t.status().name();
        r.approvalStatus = t.approvalStatus().name();
        r.version = version;
        return mapper.updateTask(r) == 1;
    }
    public TtlGovernanceRun saveRun(TtlGovernanceRun x) {
        var r = new TtlGovernanceMapper.RunRow();
        r.id = x.id();
        r.taskId = x.taskId();
        r.runNo = x.runNo();
        r.status = x.status().name();
        r.plannedKeys = x.plannedKeys();
        r.scannedKeys = x.scannedKeys();
        r.candidateKeys = x.candidateKeys();
        r.appliedKeys = x.appliedKeys();
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
    public Optional<TtlGovernanceRun> latestRun(long id) {
        return Optional.ofNullable(mapper.latestRun(id));
    }
    public TtlGovernanceCheckpoint saveCheckpoint(TtlGovernanceCheckpoint x) {
        var r = new TtlGovernanceMapper.CheckpointRow();
        r.runId = x.runId();
        r.shardId = x.shardId();
        r.cursor = x.cursor();
        r.scannedKeys = x.scannedKeys();
        r.status = x.status().name();
        mapper.upsertCheckpoint(r);
        return mapper.checkpoint(x.runId(), x.shardId());
    }
    public Optional<TtlGovernanceCheckpoint> checkpoint(long runId, String shard) {
        return Optional.ofNullable(mapper.checkpoint(runId, shard));
    }
    public List<TtlGovernanceCheckpoint> checkpoints(long runId) {
        return mapper.checkpoints(runId);
    }
}
