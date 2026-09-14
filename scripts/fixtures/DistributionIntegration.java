import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.platform.application.distribution.DistributionService;
import io.github.redisops.platform.domain.asset.*;
import io.github.redisops.platform.domain.distribution.*;
import io.github.redisops.platform.infrastructure.distribution.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Only used against disposable containers created by verify-key-distribution.mjs. */
public class DistributionIntegration {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("DISTRIBUTION_TEST_JDBC"), "root",
                    System.getenv("DISTRIBUTION_TEST_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean DistributionJson json() { return new DistributionJson(new ObjectMapper().findAndRegisterModules()); }
        @Bean DistributionRepository repository(JdbcTemplate jdbc, DistributionJson json) {
            return new JdbcDistributionRepository(jdbc, json);
        }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static DistributionSpec spec(long id) {
        return new DistributionSpec(id, 0, List.of(new DistributionRule("r", "business", "",
                DistributionRule.Kind.SEGMENTS, ":", 1)), DistributionCounter.Mode.TOP_K, 1000, 10000, 60, 200, 200);
    }
    static DistributionCheckpoint checkpoint(long count) {
        return new DistributionCheckpoint("a".repeat(64), List.of(new DistributionCheckpoint.Cursor(
                new DistributionScanPort.Shard("node", "127.0.0.1:6379"), "42", false)), 0, count, 1,
                List.of(new DistributionCounter.Entry(new DistributionClassifier.Group("r", "business", false), count, 0)),
                1000, 0);
    }
    static Object testCommand(int port, String... args) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            var out = socket.getOutputStream();
            out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes); out.write(new byte[]{13, 10});
            }
            return new BoundedResp(socket.getInputStream(), () -> {}).metadata();
        }
    }
    public static void main(String[] args) throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var repo = ctx.getBean(DistributionRepository.class);
            for (int i = 1; i <= 3; i++) jdbc.update("INSERT INTO redis_cluster(id,name,environment,owner,mode,endpoint,status) VALUES(?,?,'TEST','test','STANDALONE','127.0.0.1:1','ACTIVE')", i, "distribution-test-" + i);
            var task = repo.create(spec(1));
            var pool = Executors.newFixedThreadPool(2);
            var gate = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { gate.await(); return repo.claim("a"); });
                var b = pool.submit(() -> { gate.await(); return repo.claim("b"); });
                gate.countDown();
                var x = a.get(10, TimeUnit.SECONDS); var y = b.get(10, TimeUnit.SECONDS);
                check(x.isPresent() != y.isPresent(), "single cluster lease winner");
                var lease = x.orElseGet(y::orElseThrow);
                check(repo.claimPreview(1, "preview").isEmpty(), "preview shares lease");
                check(repo.save(lease, checkpoint(5), "RUNNING", null), "checkpoint saved");
                var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
                try { tx.execute(s -> { repo.save(lease, checkpoint(9), "RUNNING", null); throw new IllegalStateException("test rollback"); }); }
                catch (IllegalStateException expected) { }
                check(repo.checkpoint(task.id()).orElseThrow().observed() == 5, "checkpoint rollback");
                check(repo.get(task.id()).observed() == 5, "count rollback");
                check(repo.groups(task.id(), 1, 20).items().get(0).count() == 5, "groups rollback");
                try {
                    tx.execute(s -> {
                        repo.save(lease, checkpoint(9), "RUNNING", null);
                        long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        try (var killer = ctx.getBean(DataSource.class).getConnection(); var statement = killer.createStatement()) {
                            statement.execute("KILL CONNECTION " + connectionId);
                        } catch (java.sql.SQLException e) { throw new IllegalStateException("TEST_KILL_FAILED"); }
                        repo.save(lease, checkpoint(10), "RUNNING", null);
                        return null;
                    });
                    throw new AssertionError("killed transaction unexpectedly committed");
                } catch (RuntimeException expected) { }
                check(repo.get(task.id()).observed() == 5 && repo.checkpoint(task.id()).orElseThrow().observed() == 5,
                        "connection loss cannot publish partial checkpoint");
                jdbc.update("UPDATE key_distribution_runtime SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE cluster_id=1");
                var replacement = repo.claim("replacement").orElseThrow();
                check(replacement.generation() > lease.generation(), "generation advances");
                check(!repo.save(lease, checkpoint(99), "RUNNING", null), "stale owner fenced");
                check(repo.save(replacement, checkpoint(6), "RUNNING", null), "replacement saves");
                var paused = repo.control(task.id(), repo.get(task.id()).version(), "pause");
                check(!repo.renew(replacement), "pause releases lease");
                var resumed = repo.control(task.id(), paused.version(), "resume");
                repo.control(task.id(), resumed.version(), "cancel");
                System.out.println("PASS MySQL: contention, preview mutex, atomic rollback, killed connection, stale generation, pause/resume/cancel");
            } finally { pool.shutdownNow(); }
            int standalone = Integer.parseInt(args[0]), sentinel = Integer.parseInt(args[1]), cluster = Integer.parseInt(args[2]);
            RedisConnectionProfileProvider profiles = id -> new RedisConnectionProfile(id,
                    id == 1 ? ClusterMode.STANDALONE : id == 2 ? ClusterMode.SENTINEL : ClusterMode.CLUSTER,
                    List.of("127.0.0.1:" + (id == 1 ? standalone : id == 2 ? sentinel : cluster)),
                    id == 2 ? "distribution-test" : null, null, "NONE", null);
            var redis = new BoundedDistributionRedis(profiles);
            try (var service = new ServiceScope(new DistributionService(repo, redis, profiles, 1, 21600, 1000, 1000))) {
                for (long id = 1; id <= 3; id++) {
                    var topology = redis.topology(id, 0);
                    check(topology.shards().size() == (id == 3 ? 3 : 1), "primary shard coverage");
                    var preview = service.value.preview(id, 0);
                    check(!preview.samples().isEmpty(), "preview has samples");
                    var created = service.value.create(spec(id));
                    service.value.poll();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    DistributionTask current;
                    do { Thread.sleep(100); current = repo.get(created.id()); }
                    while (Set.of("QUEUED", "RUNNING").contains(current.status()) && System.nanoTime() < until);
                    check("COMPLETED".equals(current.status()), "scan completion: " + current.reason());
                    check(current.observed() == 120, "controlled observations");
                    check(current.completedShards() == topology.shards().size(), "all shards covered");
                    System.out.println("PASS Redis mode " + id + ": preview, task, observations, primary coverage");
                }
            }
            var calls = new AtomicInteger();
            DistributionScanPort switching = new DistributionScanPort() {
                public Topology topology(long id, int db) {
                    if (calls.incrementAndGet() == 2) {
                        try { testCommand(standalone, "REPLICAOF", "127.0.0.1", "1"); }
                        catch (Exception e) { throw new IllegalStateException("TEST_ROLE_CHANGE_FAILED"); }
                    }
                    return redis.topology(id, db);
                }
                public Page scan(long id, int db, Shard shard, String cursor, int count) {
                    return redis.scan(id, db, shard, cursor, count);
                }
            };
            try (var scope = new ServiceScope(new DistributionService(repo, switching, profiles, 1, 21600, 1000, 1000))) {
                var changed = scope.value.create(spec(1)); scope.value.poll();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                DistributionTask current;
                do { Thread.sleep(100); current = repo.get(changed.id()); }
                while (Set.of("QUEUED", "RUNNING").contains(current.status()) && System.nanoTime() < until);
                check("INCOMPLETE".equals(current.status()) && "SCAN_TOPOLOGY_UNSTABLE".equals(current.reason()), "role change fails closed");
                System.out.println("PASS actual Redis primary role change: incomplete, no silent rescan");
            } finally { testCommand(standalone, "REPLICAOF", "NO", "ONE"); }
            String nodes = new String((byte[]) testCommand(cluster, "CLUSTER", "NODES"), StandardCharsets.UTF_8);
            String[] lines = nodes.split("\\r?\\n");
            String[] owner = Arrays.stream(lines).map(line -> line.split(" +"))
                    .filter(fields -> Arrays.stream(fields).skip(8).anyMatch(slot -> slot.equals("0") || slot.startsWith("0-")))
                    .findFirst().orElseThrow();
            String target = Arrays.stream(lines).map(line -> line.split(" +")[0]).filter(id -> !id.equals(owner[0])).findFirst().orElseThrow();
            int ownerPort = Integer.parseInt(owner[1].split("[@,]", 2)[0].split(":")[1]);
            try {
                testCommand(ownerPort, "CLUSTER", "SETSLOT", "0", "MIGRATING", target);
                try { redis.topology(3, 0); throw new AssertionError("slot migration accepted"); }
                catch (IllegalStateException expected) { check("SCAN_TOPOLOGY_UNSTABLE".equals(expected.getMessage()), "migration reason"); }
                System.out.println("PASS actual Cluster slot migration marker: refused as unstable topology");
            } finally { testCommand(ownerPort, "CLUSTER", "SETSLOT", "0", "STABLE"); }
        }
    }
    record ServiceScope(DistributionService value) implements AutoCloseable { public void close() { value.destroy(); } }
}
