package io.github.redisops.worker;

import io.github.redisops.api.ApiResponse;
import io.github.redisops.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectorSnapshotController {
    private final RedisCollectorWorker collector;

    public CollectorSnapshotController(RedisCollectorWorker collector) {
        this.collector = collector;
    }

    @GetMapping("/api/v1/collector/clusters/{clusterId}/nodes")
    ApiResponse<List<RedisCollectorWorker.NodeView>> nodes(@PathVariable long clusterId, HttpServletRequest request) {
        return ApiResponse.of(collector.nodes(clusterId),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
