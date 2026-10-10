package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.github.susongyan.redisops.platform.domain.asset.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CONSOLE_CLUSTER_SMOKE", matches = "true")
class ConsoleClusterReadTest {
    @Test
    void readsInfoAndSlotsFromOneDemoPrimary() {
        var adapter = new LettuceRedisOperationAdapter(id -> new RedisConnectionProfile(id, ClusterMode.CLUSTER,
                List.of("127.0.0.1:7401", "127.0.0.1:7402", "127.0.0.1:7403"), null, null, "NONE", null));
        var info = adapter.execute(1, 0, "CLUSTER", List.of("INFO"), 0);
        assertTrue(info.success(), info.errorCode());
        assertTrue(info.value().contains("cluster_state:"));
        assertNotNull(info.executionNode());
        var slots = adapter.execute(1, 0, "CLUSTER", List.of("SLOTS"), 0);
        assertTrue(slots.success(), slots.errorCode());
        assertEquals("array", slots.type());
        assertNotNull(slots.executionNode());
    }
}
