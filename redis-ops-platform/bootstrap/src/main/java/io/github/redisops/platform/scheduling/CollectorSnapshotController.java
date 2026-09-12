package io.github.redisops.platform.scheduling;

import io.github.redisops.platform.api.ApiResponse;
import io.github.redisops.platform.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectorSnapshotController {
    private final org.springframework.beans.factory.ObjectProvider<RedisCollectorWorker> collector;

    public CollectorSnapshotController(
            org.springframework.beans.factory.ObjectProvider<RedisCollectorWorker> collector) {
        this.collector = collector;
    }

    @GetMapping("/api/v1/collector/clusters/{clusterId}/nodes")
    ApiResponse<List<RedisCollectorWorker.NodeView>> nodes(@PathVariable long clusterId, HttpServletRequest request) {
        RedisCollectorWorker activeCollector = collector.getIfAvailable();
        return ApiResponse.of(activeCollector == null ? List.of() : activeCollector.nodes(clusterId),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
