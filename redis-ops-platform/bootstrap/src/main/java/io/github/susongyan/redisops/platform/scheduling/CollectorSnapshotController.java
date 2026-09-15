package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.api.ApiResponse;
import io.github.susongyan.redisops.platform.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectorSnapshotController {
    private final org.springframework.beans.factory.ObjectProvider<RedisCollectorWorker> collector;
    private final MeterRegistry meters;
    private static final Set<String> METRICS = Set.of("redis_ops_collector_up", "redis_ops_redis_collector_nodes",
            "redis_ops_redis_used_memory_bytes", "redis_ops_redis_max_memory_bytes",
            "redis_ops_redis_connected_clients",
            "redis_ops_redis_ops_per_second", "redis_ops_redis_keyspace_hits_total",
            "redis_ops_redis_keyspace_misses_total",
            "redis_ops_redis_command_calls_total", "redis_ops_redis_command_usec_total",
            "redis_ops_redis_replication_backlog_bytes", "redis_ops_redis_slowlog_length");

    public CollectorSnapshotController(
            org.springframework.beans.factory.ObjectProvider<RedisCollectorWorker> collector, MeterRegistry meters) {
        this.collector = collector;
        this.meters = meters;
    }

    @GetMapping("/api/v1/collector/metrics")
    ResponseEntity<ApiResponse<Map<String, Map<String, Double>>>> metrics(HttpServletRequest request) {
        Map<String, Map<String, Double>> result = new TreeMap<>();
        if (collector.getIfAvailable() != null) {
            for (String name : METRICS) {
                for (var gauge : meters.find(name).gauges()) {
                    String clusterId = gauge.getId().getTag("cluster_id");
                    double value = gauge.value();
                    if (clusterId != null && clusterId.matches("[0-9]{1,19}") && Double.isFinite(value)) {
                        String field = name.endsWith("_total") ? name.substring(0, name.length() - 6) : name;
                        result.computeIfAbsent(clusterId, ignored -> new TreeMap<>()).put(field, value);
                    }
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }

    @GetMapping("/api/v1/collector/clusters/{clusterId}/nodes")
    ApiResponse<List<RedisCollectorWorker.NodeView>> nodes(@PathVariable long clusterId, HttpServletRequest request) {
        RedisCollectorWorker activeCollector = collector.getIfAvailable();
        return ApiResponse.of(activeCollector == null ? List.of() : activeCollector.nodes(clusterId),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
