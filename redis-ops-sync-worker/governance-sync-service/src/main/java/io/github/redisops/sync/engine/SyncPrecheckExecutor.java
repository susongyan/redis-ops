package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.sync.contract.SyncCommandCapabilities;
import io.github.redisops.sync.contract.SyncCommandCapability;
import io.github.redisops.sync.contract.SyncCommandPolicy;
import io.github.redisops.sync.worker.domain.WorkerSyncPrecheckReport;
import io.github.redisops.sync.worker.domain.WorkerSyncTask;
import io.github.redisops.sync.worker.domain.WorkerClusterView;
import io.github.redisops.sync.worker.persistence.WorkerAssetReadPort;
import io.github.redisops.sync.worker.persistence.WorkerSyncExecutionPort;
import io.github.redisops.sync.worker.persistence.WorkerTopologyPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Component
public class SyncPrecheckExecutor {
    private final WorkerAssetReadPort clusters;
    private final WorkerRedisConnectionProfilePort profiles;
    private final WorkerTopologyPort topology;
    private final WorkerSyncExecutionPort execution;
    private final ObjectMapper json;
    private final RedisDataEndpointResolver endpoints;
    private final Path dataDirectory;
    private final long segmentBytes;

    public SyncPrecheckExecutor(WorkerAssetReadPort clusters, WorkerRedisConnectionProfilePort profiles,
            WorkerTopologyPort topology, WorkerSyncExecutionPort execution, ObjectMapper json,
            RedisDataEndpointResolver endpoints,
            @Value("${sync.engine.data-dir:./data/sync}") Path dataDirectory,
            @Value("${sync.engine.segment-bytes:268435456}") long segmentBytes) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.topology = topology;
        this.execution = execution;
        this.json = json;
        this.endpoints = endpoints;
        this.dataDirectory = dataDirectory;
        this.segmentBytes = segmentBytes;
    }

    public WorkerSyncPrecheckReport execute(WorkerSyncTask task) {
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean passed = true;
        passed &= check(checks, "DISTINCT_CLUSTERS", () -> distinct(task));
        passed &= check(checks, "SOURCE_ASSET", () -> active(task.sourceClusterId()));
        passed &= check(checks, "TARGET_ASSET", () -> active(task.targetClusterId()));
        passed &= check(checks, "VERSION_COMPATIBILITY", () -> compatibleVersions(task));
        passed &= check(checks, "DATABASE_MAPPING", () -> validDatabases(task));
        passed &= check(checks, "RUNNER_TOPOLOGY", () -> supportedTopology(task));
        passed &= check(checks, "COMMAND_POLICY", () -> commandPolicySummary(task));
        passed &= check(checks, "SOURCE_CONNECTION", () -> discover(task.sourceClusterId()));
        passed &= check(checks, "TARGET_CONNECTION", () -> discover(task.targetClusterId()));
        passed &= check(checks, "RESERVED_NAMESPACE", () -> reservedNamespace(task));
        passed &= check(checks, "WORKER_SPOOL_STORAGE", this::spoolStorage);
        commandHistoryRisk(checks, task);
        Instant checked = Instant.now();
        String report;
        try {
            report = json.writeValueAsString(Map.of("passed", passed, "checks", checks));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize precheck report", e);
        }
        return execution.savePrecheck(new WorkerSyncPrecheckReport(task.id(), passed ? "PASSED" : "FAILED", report,
                checked, checked.plusSeconds(600)));
    }
    private String active(long id) {
        WorkerClusterView cluster = clusters.get(id);
        if (!cluster.active())
            throw new IllegalStateException("cluster is not ACTIVE");
        return cluster.mode() + " " + cluster.redisVersion();
    }
    private String discover(long id) {
        WorkerClusterView cluster = clusters.get(id);
        try (WorkerRedisConnectionProfile ignored = profiles.get(id)) {
            return topology.discover(cluster).size() + " nodes";
        }
    }
    private String distinct(WorkerSyncTask task) {
        if (task.sourceClusterId() == task.targetClusterId())
            throw new IllegalStateException("source and target clusters must differ");
        return task.sourceClusterId() + " -> " + task.targetClusterId();
    }
    private String compatibleVersions(WorkerSyncTask task) {
        WorkerClusterView source = clusters.get(task.sourceClusterId());
        WorkerClusterView target = clusters.get(task.targetClusterId());
        int[] sourceVersion = version(source.redisVersion());
        int[] targetVersion = version(target.redisVersion());
        supportedVersion(sourceVersion, "source");
        supportedVersion(targetVersion, "target");
        if (task.relationId() != null && (sourceVersion[0] != targetVersion[0] || sourceVersion[1] != targetVersion[1]))
            throw new IllegalStateException("disaster recovery requires matching Redis major.minor versions");
        if (task.relationId() == null && compare(sourceVersion, targetVersion) > 0)
            throw new IllegalStateException("migration from newer Redis to older Redis is not certified");
        return source.redisVersion() + " -> " + target.redisVersion();
    }
    private String validDatabases(WorkerSyncTask task) {
        WorkerClusterView source = clusters.get(task.sourceClusterId());
        WorkerClusterView target = clusters.get(task.targetClusterId());
        validateDatabase(source.mode(), task.sourceDb(), "sourceDb");
        validateDatabase(target.mode(), task.targetDb(), "targetDb");
        return task.sourceDb() + " -> " + task.targetDb();
    }
    private String supportedTopology(WorkerSyncTask task) throws Exception {
        WorkerClusterView source = clusters.get(task.sourceClusterId());
        WorkerClusterView target = clusters.get(task.targetClusterId());
        int sourceMasters = clusterMasters(task.sourceClusterId(), source.mode());
        int targetMasters = clusterMasters(task.targetClusterId(), target.mode());
        return source.mode() + " (" + sourceMasters + " channels) -> " + target.mode()
                + " (" + targetMasters + " targets)";
    }
    private int clusterMasters(long clusterId, WorkerClusterMode mode) throws Exception {
        if (mode != WorkerClusterMode.CLUSTER)
            return 1;
        try (WorkerRedisConnectionProfile profile = profiles.get(clusterId)) {
            return new HashSet<>(endpoints.resolveClusterMasters(profile).stream()
                    .map(RedisDataEndpointResolver.ClusterMaster::endpoint).toList()).size();
        }
    }
    private String reservedNamespace(WorkerSyncTask task) throws Exception {
        try (WorkerRedisConnectionProfile profile = profiles.get(task.targetClusterId())) {
            if (profile.mode() == WorkerClusterMode.CLUSTER) {
                int inspected = 0;
                Set<RedisEndpoint> masters = new LinkedHashSet<>();
                for (RedisDataEndpointResolver.ClusterMaster master : endpoints.resolveClusterMasters(profile))
                    masters.add(master.endpoint());
                for (RedisEndpoint master : masters) {
                    try (TargetCommandSession target = TargetCommandSession.clusterSlot(profile, master, task.id(),
                            java.time.Duration.ofSeconds(10), "precheck", 0)) {
                        target.assertReservedNamespaceAvailable();
                        inspected++;
                    }
                }
                return "no conflicting __redis_ops_sync_* keys on " + inspected + " Cluster masters";
            }
            try (TargetCommandSession target = new TargetCommandSession(profile, endpoints.resolvePrimary(profile),
                    task.targetDb(), task.id(), java.time.Duration.ofSeconds(10))) {
                target.assertReservedNamespaceAvailable();
                return "no conflicting __redis_ops_sync_* keys";
            }
        }
    }
    private String spoolStorage() throws Exception {
        Files.createDirectories(dataDirectory);
        long usable = Files.getFileStore(dataDirectory).getUsableSpace();
        if (usable < segmentBytes)
            throw new IllegalStateException("worker data volume cannot hold one configured spool segment");
        return "usableBytes=" + usable + ", segmentBytes=" + segmentBytes;
    }

    private String commandPolicySummary(WorkerSyncTask task) throws Exception {
        SyncCommandPolicy policy = commandPolicy(task);
        WorkerClusterView target = clusters.get(task.targetClusterId());
        long blocked = SyncCommandCapabilities.all(target.mode() == WorkerClusterMode.CLUSTER, policy).stream()
                .filter(SyncCommandCapability::currentlyBlocked)
                .count();
        return "policy=" + policy.policyVersion() + ", blockedKnownCommands=" + blocked
                + ", unknownCommands=BLOCK";
    }

    private void commandHistoryRisk(List<Map<String, Object>> checks, WorkerSyncTask task) {
        try {
            WorkerClusterView sourceCluster = clusters.get(task.sourceClusterId());
            WorkerClusterView targetCluster = clusters.get(task.targetClusterId());
            SyncCommandPolicy policy = commandPolicy(task);
            Map<String, Long> stats = sourceCommandStats(task, sourceCluster.mode());
            List<Map<String, Object>> risks = stats.entrySet().stream()
                    .map(entry -> Map.entry(entry,
                            SyncCommandCapabilities.classify(entry.getKey().split(" ", 2)[0],
                                    targetCluster.mode() == WorkerClusterMode.CLUSTER, policy)))
                    .filter(entry -> entry.getValue().currentlyBlocked())
                    .sorted(Comparator.<Map.Entry<Map.Entry<String, Long>, SyncCommandCapability>>comparingLong(
                            entry -> entry.getKey().getValue()).reversed())
                    .limit(50)
                    .map(entry -> Map.<String, Object>of(
                            "command", entry.getKey().getKey(),
                            "calls", entry.getKey().getValue(),
                            "category", entry.getValue().category(),
                            "reason", entry.getValue().reason()))
                    .toList();
            if (risks.isEmpty()) {
                checks.add(Map.of("name", "SOURCE_COMMAND_HISTORY", "status", "PASSED",
                        "message", "INFO commandstats 中未发现已知的阻塞命令"));
            } else {
                checks.add(Map.of("name", "SOURCE_COMMAND_HISTORY", "status", "WARNING",
                        "message", "源端历史统计中存在可能阻塞同步的命令；历史调用不代表同步期间一定发生",
                        "risks", risks));
            }
        } catch (Exception error) {
            checks.add(Map.of("name", "SOURCE_COMMAND_HISTORY", "status", "WARNING",
                    "message", "无法读取 INFO commandstats，不影响预检查通过：" + safe(error.getMessage())));
        }
    }

    private Map<String, Long> sourceCommandStats(WorkerSyncTask task, WorkerClusterMode mode) throws Exception {
        Map<String, Long> aggregated = new LinkedHashMap<>();
        try (WorkerRedisConnectionProfile profile = profiles.get(task.sourceClusterId())) {
            if (mode == WorkerClusterMode.CLUSTER) {
                Set<RedisEndpoint> masters = new LinkedHashSet<>();
                for (RedisDataEndpointResolver.ClusterMaster master : endpoints.resolveClusterMasters(profile))
                    masters.add(master.endpoint());
                for (RedisEndpoint master : masters) {
                    try (TargetCommandSession source = TargetCommandSession.clusterSlot(profile, master, task.id(),
                            java.time.Duration.ofSeconds(10), "precheck-source", 0)) {
                        source.commandStats().forEach((command, calls) -> aggregated.merge(command, calls, Long::sum));
                    }
                }
            } else {
                try (TargetCommandSession source = new TargetCommandSession(profile,
                        endpoints.resolvePrimary(profile), task.sourceDb(), task.id(),
                        java.time.Duration.ofSeconds(10))) {
                    aggregated.putAll(source.commandStats());
                }
            }
        }
        return aggregated;
    }

    private SyncCommandPolicy commandPolicy(WorkerSyncTask task) throws Exception {
        return json.readValue(task.commandPolicyJson(), SyncCommandPolicy.class);
    }
    private static int[] version(String value) {
        try {
            String[] fields = value.split("[.-]");
            return new int[]{Integer.parseInt(fields[0]), fields.length > 1 ? Integer.parseInt(fields[1]) : 0};
        } catch (RuntimeException error) {
            throw new IllegalStateException("invalid Redis version: " + value);
        }
    }
    private static void supportedVersion(int[] value, String side) {
        if (value[0] < 5 || value[0] > 8 || value[0] == 8 && value[1] > 4)
            throw new IllegalStateException(side + " Redis version is outside certified range 5.0-8.4");
    }
    private static int compare(int[] left, int[] right) {
        int major = Integer.compare(left[0], right[0]);
        return major == 0 ? Integer.compare(left[1], right[1]) : major;
    }
    private static void validateDatabase(WorkerClusterMode mode, int database, String field) {
        if (database < 0 || mode == WorkerClusterMode.CLUSTER && database != 0)
            throw new IllegalStateException(field + " is invalid for " + mode);
    }
    private boolean check(List<Map<String, Object>> checks, String name, Checked action) {
        try {
            checks.add(Map.of("name", name, "status", "PASSED", "message", action.run()));
            return true;
        } catch (Exception e) {
            checks.add(Map.of("name", name, "status", "FAILED", "message", safe(e.getMessage())));
            return false;
        }
    }
    private static String safe(String value) {
        return value == null ? "unknown error" : value.substring(0, Math.min(512, value.length()));
    }
    @FunctionalInterface
    private interface Checked {
        String run() throws Exception;
    }
}
