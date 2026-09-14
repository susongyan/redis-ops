package io.github.redisops.platform.infrastructure.distribution;

import io.github.redisops.platform.domain.distribution.*;
import io.github.redisops.platform.common.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDistributionRepository implements DistributionRepository {
    private final JdbcTemplate jdbc;
    private final DistributionJson json;
    public JdbcDistributionRepository(JdbcTemplate jdbc, DistributionJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }
    private DistributionTask row(ResultSet r, int n) throws SQLException {
        return new DistributionTask(r.getLong("id"), r.getLong("cluster_id"),
                json.decode(r.getString("spec_json"), DistributionSpec.class),
                r.getString("status"), r.getString("reason"), r.getLong("version"), r.getLong("observed"),
                r.getInt("completed_shards"),
                r.getInt("total_shards"), r.getLong("elapsed_millis"), r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("updated_at").toInstant(), r.getBoolean("capacity_reached"));
    }
    private static final String COLUMNS = "id,cluster_id,spec_json,status,reason,version,observed,capacity_reached,completed_shards,total_shards,elapsed_millis,created_at,updated_at";
    public DistributionTask create(DistributionSpec spec) {
        String encoded = json.encode(spec, 64 * 1024);
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var p = c.prepareStatement("INSERT INTO key_distribution_task(cluster_id,spec_json) VALUES(?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            p.setLong(1, spec.clusterId());
            p.setString(2, encoded);
            return p;
        }, key);
        return get(Objects.requireNonNull(key.getKey()).longValue());
    }
    public DistributionTask get(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM key_distribution_task WHERE id=?", this::row, id).stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("keyDistribution", id));
    }
    public PageResult<DistributionTask> list(int page, int size) {
        return new PageResult<>(
                jdbc.query("SELECT " + COLUMNS + " FROM key_distribution_task ORDER BY id DESC LIMIT ? OFFSET ?",
                        this::row, size, Math.multiplyExact(page - 1, size)),
                jdbc.queryForObject("SELECT COUNT(*) FROM key_distribution_task", Long.class), page, size);
    }
    @Transactional
    public DistributionTask control(long id, long version, String action) {
        DistributionTask task = get(id);
        String next;
        switch (action) {
            case "pause" -> {
                if (!Set.of("RUNNING", "QUEUED").contains(task.status()))
                    throw new BusinessException("INVALID_STATE", "task cannot pause");
                next = "PAUSED";
            }
            case "resume" -> {
                if (!"PAUSED".equals(task.status()))
                    throw new BusinessException("INVALID_STATE", "task cannot resume");
                next = "QUEUED";
            }
            case "cancel" -> {
                if (!Set.of("QUEUED", "RUNNING", "PAUSED").contains(task.status()))
                    throw new BusinessException("INVALID_STATE", "task cannot cancel");
                next = "CANCELLED";
            }
            default -> throw new IllegalArgumentException("INVALID_DISTRIBUTION_ACTION");
        }
        // Same runtime-before-task lock order as checkpoint publication.
        jdbc.queryForList("SELECT cluster_id FROM key_distribution_runtime WHERE cluster_id=? FOR UPDATE",
                task.clusterId());
        if (jdbc.update(
                "UPDATE key_distribution_task SET status=?,reason=NULL,version=version+1 WHERE id=? AND version=?",
                next, id, version) != 1)
            throw new BusinessException("CONCURRENT_MODIFICATION", "task changed");
        jdbc.update(
                "UPDATE key_distribution_runtime SET owner=NULL,task_id=NULL,lease_until=NULL WHERE cluster_id=? AND task_id=?",
                task.clusterId(), id);
        return get(id);
    }
    @Transactional
    public Optional<Lease> claim(String owner) {
        var tasks = jdbc.query("SELECT " + COLUMNS
                + " FROM key_distribution_task WHERE status IN ('QUEUED','RUNNING') AND NOT EXISTS (SELECT 1 FROM key_distribution_runtime r WHERE r.cluster_id=key_distribution_task.cluster_id AND r.owner IS NOT NULL AND r.lease_until>=CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 32",
                this::row);
        for (var task : tasks) {
            jdbc.update("INSERT IGNORE INTO key_distribution_runtime(cluster_id) VALUES(?)", task.clusterId());
            if (jdbc.update(
                    "UPDATE key_distribution_runtime SET task_id=?,owner=?,generation=generation+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE cluster_id=? AND (owner IS NULL OR lease_until<CURRENT_TIMESTAMP(3))",
                    task.id(), owner, task.clusterId()) != 1)
                continue;
            if (jdbc.update(
                    "UPDATE key_distribution_task SET status='RUNNING',version=version+1 WHERE id=? AND status IN ('QUEUED','RUNNING')",
                    task.id()) != 1) {
                jdbc.update(
                        "UPDATE key_distribution_runtime SET owner=NULL,task_id=NULL,lease_until=NULL WHERE cluster_id=? AND owner=?",
                        task.clusterId(), owner);
                continue;
            }
            return Optional.of(new Lease(task.clusterId(), task.id(), owner, generation(task.clusterId())));
        }
        return Optional.empty();
    }
    private long generation(long cluster) {
        return jdbc.queryForObject("SELECT generation FROM key_distribution_runtime WHERE cluster_id=?", Long.class,
                cluster);
    }
    @Transactional
    public Optional<Lease> claimPreview(long cluster, String owner) {
        jdbc.update("INSERT IGNORE INTO key_distribution_runtime(cluster_id) VALUES(?)", cluster);
        if (jdbc.update(
                "UPDATE key_distribution_runtime SET task_id=NULL,owner=?,generation=generation+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND),preview_after=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 15 SECOND) WHERE cluster_id=? AND (owner IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) AND (preview_after IS NULL OR preview_after<CURRENT_TIMESTAMP(3))",
                owner, cluster) != 1)
            return Optional.empty();
        return Optional.of(new Lease(cluster, 0, owner, generation(cluster)));
    }
    public boolean renew(Lease l) {
        return jdbc.update(
                "UPDATE key_distribution_runtime SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE cluster_id=? AND owner=? AND generation=? AND lease_until>CURRENT_TIMESTAMP(3)",
                l.clusterId(), l.owner(), l.generation()) == 1;
    }
    public void release(Lease l) {
        jdbc.update(
                "UPDATE key_distribution_runtime SET owner=NULL,task_id=NULL,lease_until=NULL WHERE cluster_id=? AND owner=? AND generation=?",
                l.clusterId(), l.owner(), l.generation());
    }
    public Optional<DistributionCheckpoint> checkpoint(long taskId) {
        String state = jdbc.queryForObject("SELECT checkpoint_json FROM key_distribution_task WHERE id=?", String.class,
                taskId);
        return Optional.ofNullable(state).map(s -> json.decode(s, DistributionCheckpoint.class));
    }
    @Transactional
    public boolean save(Lease l, DistributionCheckpoint c, String status, String reason) {
        if (!Set.of("RUNNING", "PAUSED", "COMPLETED", "INCOMPLETE", "FAILED", "CANCELLED").contains(status)
                || reason != null && !reason.matches("[A-Z_]{1,64}"))
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_STATE");
        String state = c == null ? null : json.encode(c, 2 * 1024 * 1024);
        if (!renew(l))
            return false;
        int updated = jdbc.update(
                "UPDATE key_distribution_task SET status=?,reason=?,checkpoint_json=COALESCE(?,checkpoint_json),observed=COALESCE(?,observed),completed_shards=COALESCE(?,completed_shards),total_shards=COALESCE(?,total_shards),elapsed_millis=COALESCE(?,elapsed_millis),version=version+1 WHERE id=? AND status='RUNNING'",
                status, reason, state,
                c == null ? null : c.observed(),
                c == null ? null : c.cursors().stream().filter(DistributionCheckpoint.Cursor::completed).count(),
                c == null ? null : c.cursors().size(), c == null ? null : c.elapsedMillis(), l.taskId());
        if (updated != 1)
            return false;
        if (c != null) {
            jdbc.update(
                    "UPDATE key_distribution_task SET capacity_reached=(? >= CAST(JSON_UNQUOTE(JSON_EXTRACT(spec_json,'$.capacity')) AS UNSIGNED)) WHERE id=?",
                    c.entries().stream().filter(e -> !e.group().system()).count(), l.taskId());
            jdbc.update("DELETE FROM key_distribution_group WHERE task_id=?", l.taskId());
            jdbc.batchUpdate(
                    "INSERT INTO key_distribution_group(task_id,ordinal_no,rule_id,group_text,system_bucket,observed_count,error_count) VALUES(?,?,?,?,?,?,?)",
                    new BatchPreparedStatementSetter() {
                        public int getBatchSize() {
                            return c.entries().size();
                        }
                        public void setValues(PreparedStatement p, int i) throws SQLException {
                            var e = c.entries().get(i);
                            p.setLong(1, l.taskId());
                            p.setInt(2, i);
                            p.setString(3, e.group().ruleId());
                            p.setString(4, e.group().text());
                            p.setBoolean(5, e.group().system());
                            p.setLong(6, e.count());
                            p.setLong(7, e.error());
                        }
                    });
        }
        if (!"RUNNING".equals(status))
            release(l);
        return true;
    }
    public PageResult<DistributionCounter.Entry> groups(long id, int page, int size) {
        get(id);
        var entries = jdbc.query(
                "SELECT rule_id,group_text,system_bucket,observed_count,error_count FROM key_distribution_group WHERE task_id=? ORDER BY ordinal_no LIMIT ? OFFSET ?",
                (r, n) -> new DistributionCounter.Entry(
                        new DistributionClassifier.Group(r.getString(1), r.getString(2), r.getBoolean(3)), r.getLong(4),
                        r.getLong(5)),
                id, size, Math.multiplyExact(page - 1, size));
        return new PageResult<>(entries,
                jdbc.queryForObject("SELECT COUNT(*) FROM key_distribution_group WHERE task_id=?", Long.class, id),
                page, size);
    }
    public void cleanup(int days) {
        jdbc.update(
                "DELETE FROM key_distribution_task WHERE status IN ('COMPLETED','INCOMPLETE','FAILED','CANCELLED') AND updated_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL ? DAY) ORDER BY id LIMIT 20",
                days);
    }
}
