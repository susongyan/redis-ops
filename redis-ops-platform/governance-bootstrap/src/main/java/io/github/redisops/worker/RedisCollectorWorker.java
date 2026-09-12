package io.github.redisops.worker;

import io.github.redisops.common.PageResult;
import io.github.redisops.application.alert.AlertService;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.collector.CollectorRunRepository;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Read-only baseline collector. Raw INFO is never persisted; only bounded numeric gauges are exported. */
@Component
@ConditionalOnProperty(name = "collector.enabled", havingValue = "true", matchIfMissing = true)
public class RedisCollectorWorker {
    private final ClusterRepository clusters;
    private final RedisConnectionProfileProvider profiles;
    private final MeterRegistry meters;
    private final AlertService alerts;
    private final CollectorRunRepository runs;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private final Map<String, List<NodeSnapshot>> nodeSnapshots = new ConcurrentHashMap<>();
    private final int pageSize;

    public RedisCollectorWorker(ClusterRepository clusters, RedisConnectionProfileProvider profiles,
            MeterRegistry meters, AlertService alerts, CollectorRunRepository runs,
            @Value("${collector.page-size:200}") int pageSize) {
        this.clusters = clusters;
        this.profiles = profiles;
        this.meters = meters;
        this.alerts = alerts;
        this.runs = runs;
        this.pageSize = pageSize;
    }

    @Scheduled(fixedDelayString = "${collector.fast-interval-ms:15000}")
    public void collect() {
        PageResult<RedisCluster> page = clusters.findAll(new ClusterQuery(null, null, null, ClusterStatus.ACTIVE, 1,
                pageSize));
        for (RedisCluster cluster : page.items())
            collect(cluster);
    }

    private void collect(RedisCluster cluster) {
        String tag = Long.toString(cluster.id());
        long runId = runs.start(cluster.id(), "FAST");
        try (RedisConnectionProfile profile = profiles.get(cluster.id())) {
            List<NodeSnapshot> nodes = switch (profile.mode()) {
                case STANDALONE -> List.of(node("standalone", profile.seedEndpoints().get(0),
                        info(uri(profile.seedEndpoints().get(0), profile)), "master"));
                case SENTINEL ->
                    List.of(node("sentinel-master", "resolved", info(resolveSentinelMaster(profile)), "master"));
                case CLUSTER -> clusterInfos(profile);
            };
            nodeSnapshots.put(tag, List.copyOf(nodes));
            publish(tag, nodes.stream().map(NodeSnapshot::info).toList());
            runs.finish(runId, "SUCCEEDED", summary(nodes), null, null);
        } catch (RuntimeException error) {
            put("redis_ops_collector_up", tag, 0);
            alerts.trigger("COLLECTOR_UNAVAILABLE", "REDIS_CLUSTER", tag, 1d,
                    "Collector could not obtain a Redis INFO snapshot");
            runs.finish(runId, "FAILED", "{}", "COLLECTOR_ERROR", "Collector snapshot failed");
        }
    }
    private static String summary(List<NodeSnapshot> nodes) {
        return "{\"nodes\":" + nodes.size() + ",\"connectedClients\":"
                + sum(nodes.stream().map(NodeSnapshot::info).toList(), "connected_clients") + ",\"usedMemoryBytes\":"
                + sum(nodes.stream().map(NodeSnapshot::info).toList(), "used_memory") + "}";
    }

    private void publish(String clusterId, List<Map<String, String>> snapshots) {
        put("redis_ops_collector_up", clusterId, 1);
        put("redis_ops_redis_collector_nodes", clusterId, snapshots.size());
        put("redis_ops_redis_used_memory_bytes", clusterId, sum(snapshots, "used_memory"));
        put("redis_ops_redis_max_memory_bytes", clusterId, sum(snapshots, "maxmemory"));
        put("redis_ops_redis_connected_clients", clusterId, sum(snapshots, "connected_clients"));
        put("redis_ops_redis_ops_per_second", clusterId, sum(snapshots, "instantaneous_ops_per_sec"));
        put("redis_ops_redis_keyspace_hits_total", clusterId, sum(snapshots, "keyspace_hits"));
        put("redis_ops_redis_keyspace_misses_total", clusterId, sum(snapshots, "keyspace_misses"));
        put("redis_ops_redis_replication_backlog_bytes", clusterId, sum(snapshots, "repl_backlog_histlen"));
        put("redis_ops_redis_command_calls_total", clusterId, commandStatSum(snapshots, "calls"));
        put("redis_ops_redis_command_usec_total", clusterId, commandStatSum(snapshots, "usec"));
        put("redis_ops_redis_slowlog_length", clusterId, sum(snapshots, "redis_ops_slowlog_len"));
        alerts.trigger("REDIS_MEMORY_HIGH", "REDIS_CLUSTER", clusterId,
                sum(snapshots, "used_memory"), "Collector observed Redis memory usage above the configured threshold");
    }

    private List<NodeSnapshot> clusterInfos(RedisConnectionProfile profile) {
        RedisURI seed = uri(profile.seedEndpoints().get(0), profile);
        RedisClient client = RedisClient.create(seed);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            List<NodeSnapshot> result = new ArrayList<>();
            for (String line : connection.sync().clusterNodes().split("\\R")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 3 || !fields[2].contains("master") || fields[2].contains("fail"))
                    continue;
                String endpoint = fields[1].split("@", 2)[0];
                result.add(node(fields[0], endpoint, info(uri(endpoint, profile)), "master"));
            }
            if (result.isEmpty())
                throw new IllegalStateException("no reachable cluster masters");
            return result;
        } finally {
            client.shutdown();
        }
    }

    private RedisURI resolveSentinelMaster(RedisConnectionProfile profile) {
        RuntimeException failure = null;
        for (String seed : profile.seedEndpoints()) {
            RedisURI uri = uri(seed, profile);
            RedisClient client = RedisClient.create(uri);
            try (StatefulRedisSentinelConnection<String, String> sentinel = client.connectSentinel(uri)) {
                Map<String, String> master = sentinel.sync().master(profile.sentinelMasterName());
                if (master != null && master.containsKey("ip") && master.containsKey("port"))
                    return uri(master.get("ip") + ":" + master.get("port"), profile);
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                client.shutdown();
            }
        }
        throw failure == null ? new IllegalStateException("Sentinel master not found") : failure;
    }

    private Map<String, String> info(RedisURI uri) {
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            Map<String, String> snapshot = parse(connection.sync().info("ALL"));
            Long slowlog = connection.sync().slowlogLen();
            snapshot.put("redis_ops_slowlog_len", Long.toString(slowlog == null ? 0 : slowlog));
            return snapshot;
        } finally {
            client.shutdown();
        }
    }

    private void put(String name, String clusterId, double value) {
        String key = name + ':' + clusterId;
        values.put(key, value);
        Gauge.builder(name, values, map -> map.getOrDefault(key, 0d)).tag("cluster_id", clusterId).register(meters);
    }
    private static RedisURI uri(String endpoint, RedisConnectionProfile profile) {
        int split = endpoint.lastIndexOf(':');
        RedisURI uri = RedisURI.Builder
                .redis(endpoint.substring(0, split), Integer.parseInt(endpoint.substring(split + 1))).build();
        if (profile.password() != null) {
            if (profile.username() != null && !profile.username().isBlank())
                uri.setUsername(profile.username());
            uri.setPassword(profile.password());
        }
        return uri;
    }
    private static Map<String, String> parse(String value) {
        return java.util.Arrays.stream(value.split("\\r?\\n")).filter(x -> x.contains(":"))
                .map(x -> x.split(":", 2))
                .collect(java.util.stream.Collectors.toMap(x -> x[0], x -> x[1], (a, b) -> b));
    }
    public List<NodeView> nodes(long clusterId) {
        return nodeSnapshots.getOrDefault(Long.toString(clusterId), List.of()).stream()
                .map(x -> new NodeView(x.nodeId(), x.endpoint(), x.role(), number(x.info(), "connected_clients"),
                        number(x.info(), "used_memory"), number(x.info(), "maxmemory"),
                        number(x.info(), "instantaneous_ops_per_sec"), x.collectedAt()))
                .toList();
    }
    private static NodeSnapshot node(String id, String endpoint, Map<String, String> info, String role) {
        return new NodeSnapshot(id, endpoint, role, info, Instant.now());
    }
    private static double number(Map<String, String> values, String name) {
        try {
            return Double.parseDouble(values.getOrDefault(name, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    private static double sum(List<Map<String, String>> snapshots, String field) {
        return snapshots.stream().mapToDouble(v -> number(v, field)).sum();
    }
    private static double commandStatSum(List<Map<String, String>> snapshots, String field) {
        return snapshots.stream().flatMap(values -> values.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith("cmdstat_"))
                .mapToDouble(entry -> commandStat(entry.getValue(), field)).sum();
    }
    private static double commandStat(String raw, String field) {
        for (String part : raw.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && field.equals(pair[0]))
                try {
                    return Double.parseDouble(pair[1]);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
        }
        return 0;
    }
    private record NodeSnapshot(String nodeId, String endpoint, String role, Map<String, String> info,
            Instant collectedAt) {
    }
    public record NodeView(String nodeId, String endpoint, String role, double connectedClients, double usedMemoryBytes,
            double maxMemoryBytes, double opsPerSecond, Instant collectedAt) {
    }
}
