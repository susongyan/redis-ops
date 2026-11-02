package io.github.susongyan.redisops.platform.infrastructure.persistence;
import io.github.susongyan.redisops.platform.common.PageResult;
import io.github.susongyan.redisops.platform.domain.risk.*;
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
        r.groupDelimiter = t.groupDelimiter();
        r.groupSegments = t.groupSegments();
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
    public Optional<RiskScanTask> findActiveTaskForCluster(long clusterId) {
        return Optional.ofNullable(m.findActiveTaskForCluster(clusterId));
    }
    public boolean updateTask(RiskScanTask t, long v) {
        return m.updateTask(t) == 1;
    }
    public RiskScanRun saveRun(RiskScanRun r) {
        RiskScanMapper.RiskScanRunRow x = new RiskScanMapper.RiskScanRunRow();
        x.id = r.id();
        x.taskId = r.taskId();
        x.runNo = r.runNo();
        x.status = r.status().name();
        x.plannedKeys = r.plannedKeys();
        x.scannedKeys = r.scannedKeys();
        x.findingCount = r.findingCount();
        x.noTtlTotal = r.noTtlTotal();
        x.largeKeyTotal = r.largeKeyTotal();
        x.startedAt = r.startedAt();
        x.completedAt = r.completedAt();
        x.errorCode = r.errorCode();
        x.heartbeat = java.time.Instant.now();
        if (r.id() == null) {
            m.insertRun(x);
            return new RiskScanRun(x.id, r.taskId(), r.runNo(), r.status(), r.plannedKeys(), r.scannedKeys(),
                    r.findingCount(), r.noTtlTotal(), r.largeKeyTotal(), r.startedAt(), r.completedAt(),
                    r.errorCode());
        }
        m.updateRun(x);
        return r;
    }
    public Optional<RiskScanRun> latestRun(long id) {
        return Optional.ofNullable(m.latestRun(id));
    }
    @Override
    public boolean failRun(long runId) {
        return m.failRun(runId, java.time.Instant.now()) > 0;
    }
    @Override
    public List<RiskScanTask> findStaleQueuedTasks(int staleMinutes) {
        return m.findStaleQueuedTasks(staleMinutes);
    }
    @Override
    public List<RiskScanRun> findStaleRuns(java.time.Instant threshold) {
        return m.findStaleRuns(threshold);
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
        // one multi-VALUES statement per chunk: per-row inserts cost a remote-DB round trip each
        for (int from = 0; from < f.size(); from += 500)
            m.insertFindings(f.subList(from, Math.min(f.size(), from + 500)));
    }
    public PageResult<RiskFinding> findings(long id, int p, int s, String riskType) {
        return new PageResult<>(m.findings(id, (p - 1) * s, s, riskType), m.findingCount(id, riskType), p, s);
    }
    public long findingCountByType(long runId, String riskType) {
        return m.findingCountByType(runId, riskType);
    }
    public void saveGroups(List<RiskFindingGroup> groups) {
        if (groups.isEmpty())
            return;
        for (int from = 0; from < groups.size(); from += 500)
            m.upsertGroups(groups.subList(from, Math.min(groups.size(), from + 500)));
    }
    public List<String> groupTexts(long runId) {
        return m.groupTexts(runId);
    }
    public PageResult<RiskFindingGroup> groups(long runId, int p, int s) {
        List<RiskFindingGroup> items = m.findGroups(runId, (p - 1) * s, s).stream()
                .map(row -> new RiskFindingGroup(row.runId, row.riskType, row.groupText, row.hitCount, row.sampleKey,
                        row.maxMemoryBytes))
                .toList();
        return new PageResult<>(items, m.groupCount(runId), p, s);
    }
}
