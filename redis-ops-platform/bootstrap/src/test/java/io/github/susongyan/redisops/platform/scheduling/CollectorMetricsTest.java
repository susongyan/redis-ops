package io.github.susongyan.redisops.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

class CollectorMetricsTest {
    @Test
    void exportsOnlyFiniteAllowlistedClusterNumbersAndNormalizesTotals() {
        var registry = new SimpleMeterRegistry();
        try {
            ObjectProvider<RedisCollectorWorker> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(mock(RedisCollectorWorker.class));
            Gauge.builder("redis_ops_redis_keyspace_hits_total", () -> 42).tag("cluster_id", "1").register(registry);
            Gauge.builder("jvm_memory_used_bytes", () -> 99).tag("cluster_id", "1").register(registry);
            Gauge.builder("redis_ops_collector_up", () -> 1).tag("cluster_id", "invalid").register(registry);
            Gauge.builder("redis_ops_redis_used_memory_bytes", () -> Double.NaN).tag("cluster_id", "1")
                    .register(registry);
            var controller = new CollectorSnapshotController(provider, registry);
            var response = controller.metrics(new MockHttpServletRequest());
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody().data()).containsOnlyKeys("1");
            assertThat(response.getBody().data().get("1"))
                    .containsExactlyEntriesOf(java.util.Map.of("redis_ops_redis_keyspace_hits", 42d));
            when(provider.getIfAvailable()).thenReturn(null);
            assertThat(controller.metrics(new MockHttpServletRequest()).getBody().data()).isEmpty();
        } finally {
            registry.close();
        }
    }
}
