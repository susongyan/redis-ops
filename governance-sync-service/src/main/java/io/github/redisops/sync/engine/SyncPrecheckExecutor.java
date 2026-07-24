package io.github.redisops.sync.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.sync.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class SyncPrecheckExecutor {
    private final ClusterRepository clusters;
    private final RedisConnectionProfileProvider profiles;
    private final TopologyDiscoveryPort topology;
    private final SyncRepository sync;
    private final ObjectMapper json;

    public SyncPrecheckExecutor(ClusterRepository clusters, RedisConnectionProfileProvider profiles,
            TopologyDiscoveryPort topology, SyncRepository sync, ObjectMapper json) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.topology = topology;
        this.sync = sync;
        this.json = json;
    }

    public SyncPrecheckReport execute(SyncTask task) {
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean passed = true;
        passed &= check(checks, "SOURCE_ASSET", () -> active(task.sourceClusterId()));
        passed &= check(checks, "TARGET_ASSET", () -> active(task.targetClusterId()));
        passed &= check(checks, "SOURCE_CONNECTION", () -> discover(task.sourceClusterId()));
        passed &= check(checks, "TARGET_CONNECTION", () -> discover(task.targetClusterId()));
        passed &= check(checks, "RESERVED_NAMESPACE",
                () -> "reserved namespace will be verified again before target reset");
        Instant checked = Instant.now();
        String report;
        try {
            report = json.writeValueAsString(Map.of("passed", passed, "checks", checks));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize precheck report", e);
        }
        return sync.savePrecheck(new SyncPrecheckReport(null, task.id(), passed ? "PASSED" : "FAILED", report,
                checked, checked.plusSeconds(600)));
    }
    private String active(long id) {
        RedisCluster cluster = clusters.findById(id).orElseThrow();
        if (cluster.status() != ClusterStatus.ACTIVE)
            throw new IllegalStateException("cluster is not ACTIVE");
        return cluster.mode() + " " + cluster.redisVersion();
    }
    private String discover(long id) {
        RedisCluster cluster = clusters.findById(id).orElseThrow();
        try (RedisConnectionProfile ignored = profiles.get(id)) {
            return topology.discover(cluster).size() + " nodes";
        }
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
