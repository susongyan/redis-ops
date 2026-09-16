package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in disposable local MySQL. Creates and drops only a fresh randomly named fixture database. */
class WorkerPolicyMysqlTest {
    private SqlSession session;
    private String database;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("SYNC_POLICY_TEST_MYSQL");
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://127.0.0.1:"));
        var configuration = new Configuration(new Environment("fixture", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, "root", "")));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WorkerControlJobMapper.class);
        configuration.addMapper(WorkerSyncMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        database = "sync_policy_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE DATABASE " + database);
        execute("USE " + database);
        var columns = new ArrayList<String>();
        for (var field : WorkerSyncTask.class.getRecordComponents()) {
            String name = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            String type = field.getType() == boolean.class
                    ? "BOOLEAN DEFAULT FALSE"
                    : field.getType() == int.class || field.getType() == long.class || field.getType() == Long.class
                            ? "BIGINT DEFAULT 0"
                            : field.getType() == java.time.Instant.class
                                    ? "DATETIME(3)"
                                    : name.endsWith("_json") ? "JSON" : "TEXT";
            columns.add(name + " " + type);
        }
        execute("CREATE TABLE sync_task (" + String.join(",", columns) + ", PRIMARY KEY(id))");
        execute("""
                CREATE TABLE async_job (id BIGINT PRIMARY KEY,job_type VARCHAR(64),biz_id BIGINT,status VARCHAR(32),
                lease_owner VARCHAR(160),lease_until DATETIME(3),attempts INT DEFAULT 0,
                next_run_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))
                """);
        execute("""
                CREATE TABLE sync_runtime (task_id BIGINT PRIMARY KEY,runtime_id VARCHAR(36),worker_ip VARCHAR(255),
                worker_ip_runtime_id VARCHAR(128),takeover_count INT DEFAULT 0,recovery_action VARCHAR(64),
                phase VARCHAR(32),lease_owner VARCHAR(160),lease_until DATETIME(3),fencing_generation BIGINT DEFAULT 0,
                heartbeat_at DATETIME(3),started_at DATETIME(3))
                """);
    }
    @AfterEach
    void cleanup() throws Exception {
        if (session != null) {
            try {
                if (database != null && database.matches("sync_policy_test_[0-9a-f]{32}"))
                    execute("DROP DATABASE " + database);
            } finally {
                session.close();
            }
        }
    }
    @Test
    void unknownVersionsAreExcludedFromJobsRecoveryAndRuntimeClaims() throws Exception {
        List<String> incompatible = List.of("{\"policyVersion\":\"v999\"}", "{\"policyVersion\":\"V1\"}",
                "{\"policyVersion\":1}", "{\"policyVersion\":\"null\"}", "[]", "null");
        for (int i = 0; i < incompatible.size(); i++)
            insert(i + 1, incompatible.get(i));
        insert(100, "{\"policyVersion\":\"v1\"}");
        var tasks = session.getMapper(WorkerSyncMapper.class);
        var jobs = session.getMapper(WorkerControlJobMapper.class);
        assertEquals(List.of(100L), tasks.findExpiredRecoverableTasks(50).stream().map(WorkerSyncTask::id).toList());
        assertEquals(1, jobs.claim("SYNC_START", "job-owner", 30));
        assertEquals(100, jobs.findClaimed("job-owner").taskId());
        assertEquals(0, jobs.claim("SYNC_START", "another-job-owner", 30));
        for (int i = 0; i < incompatible.size(); i++)
            assertEquals(0, tasks.claimRuntime(i + 1, "new-runtime", "worker", 30, "127.0.0.1"));
        assertEquals(1, tasks.claimRuntime(100, "new-runtime", "worker", 30, "127.0.0.1"));
        execute("UPDATE async_job SET job_type='SYNC_RESUME',status='PENDING',lease_owner=NULL,lease_until=NULL");
        assertEquals(1, jobs.claimRouted("SYNC_RESUME", "routed", "worker", 30, true));
        assertEquals(100, jobs.findClaimed("routed").taskId());
        assertEquals(0, jobs.claimRouted("SYNC_RESUME", "another-route", "worker", 30, true));
    }
    @Test
    void missingNullAndEmptyVersionsKeepLegacyV1Admission() throws Exception {
        var jobs = session.getMapper(WorkerControlJobMapper.class);
        List<String> legacy = List.of("{}", "{\"policyVersion\":null}", "{\"policyVersion\":\"\"}",
                "{\"policyVersion\":\"v2\"}", "{\"policyVersion\":\"v3\"}");
        for (int i = 0; i < legacy.size(); i++) {
            insert(i + 1, legacy.get(i));
            assertEquals(1, jobs.claim("SYNC_START", "legacy-" + i, 30));
            assertEquals(i + 1, jobs.findClaimed("legacy-" + i).taskId());
        }
    }
    private void insert(long id, String policy) throws Exception {
        try (var insert = session.getConnection().prepareStatement(
                "INSERT INTO sync_task(id,command_policy_json,status) VALUES (?,?,'INCR_SYNCING')")) {
            insert.setLong(1, id);
            insert.setString(2, policy);
            insert.executeUpdate();
        }
        execute("INSERT INTO async_job(id,biz_id,job_type,status) VALUES (" + id + "," + id
                + ",'SYNC_START','PENDING')");
        execute("INSERT INTO sync_runtime(task_id,runtime_id,lease_owner,lease_until) VALUES (" + id
                + ",'old-runtime','old-worker',DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE))");
    }
    private void execute(String sql) throws Exception {
        try (var statement = session.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }
}
