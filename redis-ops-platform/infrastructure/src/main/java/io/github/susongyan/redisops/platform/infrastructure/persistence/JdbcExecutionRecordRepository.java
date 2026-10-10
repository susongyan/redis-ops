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
            executing = !jdbc.queryForList(
                    "SELECT id FROM async_job WHERE job_type=? AND biz_id=? AND status IN ('PENDING','RETRY','RUNNING') FOR UPDATE",
                    type == Type.RISK_SCAN ? "RISK_SCAN" : "DATA_VALIDATION", id).isEmpty();
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
}
