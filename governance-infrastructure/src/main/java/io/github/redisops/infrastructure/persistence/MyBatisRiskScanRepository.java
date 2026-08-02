package io.github.redisops.infrastructure.persistence;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.risk.*;
import java.util.*;
import org.springframework.stereotype.Repository;
@Repository
public class MyBatisRiskScanRepository implements RiskScanRepository {
    private final RiskScanMapper m;
    public MyBatisRiskScanRepository(RiskScanMapper m) {
        this.m = m;
    }
    public RiskScanTask saveTask(RiskScanTask t) {
        RiskScanMapper.RiskScanTaskRow r = new RiskScanMapper.RiskScanTaskRow();
        r.taskNo = t.taskNo();
        r.clusterId = t.clusterId();
        r.databaseNo = t.databaseNo();
        r.includePattern = t.includePattern();
        r.checkLargeKey = t.checkLargeKey();
        r.checkNoTtl = t.checkNoTtl();
        r.largeKeyThresholdBytes = t.largeKeyThresholdBytes();
        r.scanRatePerSecond = t.scanRatePerSecond();
        r.maxFindings = t.maxFindings();
        r.status = t.status().name();
        m.insertTask(r);
        return m.findTask(r.id);
    }
    public Optional<RiskScanTask> findTask(long id) {
        return Optional.ofNullable(m.findTask(id));
    }
    public List<RiskScanTask> findTasks() {
        return m.findTasks();
    }
    public boolean updateTask(RiskScanTask t, long v) {
        return m.updateTask(t) == 1;
    }
    public RiskScanRun saveRun(RiskScanRun r) {
        if (r.id() == null) {
            RiskScanMapper.RiskScanRunRow x = new RiskScanMapper.RiskScanRunRow();
            x.taskId = r.taskId();
            x.runNo = r.runNo();
            x.status = r.status().name();
            x.plannedKeys = r.plannedKeys();
            x.scannedKeys = r.scannedKeys();
            x.findingCount = r.findingCount();
            x.startedAt = r.startedAt();
            x.completedAt = r.completedAt();
            x.errorCode = r.errorCode();
            m.insertRun(x);
            return new RiskScanRun(x.id, r.taskId(), r.runNo(), r.status(), r.plannedKeys(), r.scannedKeys(),
                    r.findingCount(),
                    r.startedAt(), r.completedAt(), r.errorCode());
        }
        m.updateRun(r);
        return r;
    }
    public Optional<RiskScanRun> latestRun(long id) {
        return Optional.ofNullable(m.latestRun(id));
    }
    public Optional<RiskScanCheckpoint> checkpoint(long runId, String shardId) {
        return Optional.ofNullable(m.checkpoint(runId, shardId));
    }
    public List<RiskScanCheckpoint> checkpoints(long runId) {
        return m.checkpoints(runId);
    }
    public void saveCheckpoint(RiskScanCheckpoint checkpoint) {
        m.saveCheckpoint(checkpoint);
    }
    public void saveFindings(List<RiskFinding> f) {
        f.forEach(m::insertFinding);
    }
    public PageResult<RiskFinding> findings(long id, int p, int s, String riskType) {
        return new PageResult<>(m.findings(id, (p - 1) * s, s, riskType), m.findingCount(id, riskType), p, s);
    }
    public long findingCountByType(long runId, String riskType) {
        return m.findingCountByType(runId, riskType);
    }
}
