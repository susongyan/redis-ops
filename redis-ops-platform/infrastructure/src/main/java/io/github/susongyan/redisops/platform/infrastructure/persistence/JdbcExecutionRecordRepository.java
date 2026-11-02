package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository;
import io.github.susongyan.redisops.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExecutionRecordRepository implements ExecutionRecordRepository {
    private final JdbcTemplate jdbc;
    public JdbcExecutionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    private static String table(Type type) {
        return switch (type) {
            case RISK_SCAN -> "scan_task";
            case KEY_DISTRIBUTION -> "key_distribution_task";
            case VALIDATION -> "validation_task";
            case ANALYSIS -> "analysis_run";
        };
    }
    public Snapshot lock(Type type, long id) {
        boolean executing = false;
        if (type == Type.KEY_DISTRIBUTION) {
            var clusters = jdbc.queryForList(
                    "SELECT cluster_id FROM key_distribution_task WHERE id=? AND deleted_at IS NULL", Long.class, id);
            if (clusters.isEmpty())
                throw BusinessException.notFound("executionRecord", id);
            var runtimes = jdbc.queryForList(
                    "SELECT task_id,owner FROM key_distribution_runtime WHERE cluster_id=? FOR UPDATE",
                    clusters.get(0));
            executing = runtimes.stream().anyMatch(
                    r -> r.get("task_id") instanceof Number task && task.longValue() == id && r.get("owner") != null);
        } else if (type == Type.RISK_SCAN || type == Type.VALIDATION) {
            // validation/risk moved off async_job onto the LocalJobRunner; an active run row is the
            // live-execution signal and also covers the task-just-turned-terminal wrap-up race.
            executing = !jdbc.queryForList(
                    "SELECT id FROM " + (type == Type.RISK_SCAN ? "scan_run" : "validation_run")
                            + " WHERE task_id=? AND status='RUNNING' FOR UPDATE",
                    id).isEmpty();
        }
        String linked = type == Type.VALIDATION ? "sync_task_id IS NOT NULL" : "FALSE";
        final boolean active = executing;
        return jdbc.query(
                "SELECT version,status," + linked + " AS linked FROM " + table(type)
                        + " WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                (r, n) -> new Snapshot(r.getLong("version"), r.getString("status"), r.getBoolean("linked"), active), id)
                .stream().findFirst().orElseThrow(() -> BusinessException.notFound("executionRecord", id));
    }
    public boolean delete(Type type, long id, long version) {
        return jdbc.update("UPDATE " + table(type)
                + " SET deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL",
                id, version) == 1;
    }
    @Override
    public int purgeDetails(Type type, long id) {
        // Run-owned children first, then the runs themselves, then task-owned rows — all inside the
        // caller's delete transaction.
        int purged = 0;
        switch (type) {
            case RISK_SCAN -> {
                purged += deleteByRun("risk_finding", "scan_run", id);
                purged += deleteByRun("risk_finding_group", "scan_run", id);
                purged += deleteByRun("scan_shard_checkpoint", "scan_run", id);
                purged += jdbc.update("DELETE FROM scan_run WHERE task_id=?", id);
            }
            case VALIDATION -> {
                purged += deleteByRun("validation_difference", "validation_run", id);
                purged += deleteByRun("validation_shard_checkpoint", "validation_run", id);
                purged += jdbc.update("DELETE FROM validation_run WHERE task_id=?", id);
            }
            case KEY_DISTRIBUTION -> {
                purged += jdbc.update("DELETE FROM key_distribution_group WHERE task_id=?", id);
                purged += jdbc.update("DELETE FROM key_distribution_runtime WHERE task_id=?", id);
            }
            case ANALYSIS -> {
            }
        }
        return purged;
    }
    private int deleteByRun(String childTable, String runTable, long taskId) {
        return jdbc.update("DELETE FROM " + childTable + " WHERE run_id IN (SELECT id FROM " + runTable
                + " WHERE task_id=?)", taskId);
    }
}
