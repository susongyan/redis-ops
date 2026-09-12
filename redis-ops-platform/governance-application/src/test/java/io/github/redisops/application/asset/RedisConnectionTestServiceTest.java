package io.github.redisops.application.asset;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.ClusterMode;
import io.github.redisops.domain.asset.ClusterRepository;
import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.domain.asset.RedisConnectionTestPort;
import io.github.redisops.domain.asset.RedisConnectionTestResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConnectionTestServiceTest {
    @Test
    void newAuthenticatedConfigurationRequiresPassword() {
        var service = new RedisConnectionTestService(mock(ClusterRepository.class),
                mock(RedisConnectionProfileProvider.class), mock(RedisConnectionTestPort.class));
        assertThrows(BusinessException.class,
                () -> service.test(null, ClusterMode.STANDALONE, "redis:6379", true, null, null));
    }

    @Test
    void editCanReuseSavedPassword() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        RedisConnectionProfileProvider profiles = mock(RedisConnectionProfileProvider.class);
        RedisConnectionTestPort port = (mode, endpoint, username, password) -> {
            assertEquals(ClusterMode.CLUSTER, mode);
            assertEquals("new-a:6379,new-b:6379", endpoint);
            assertEquals("new-user", username);
            assertArrayEquals("saved-secret".toCharArray(), password);
            return new RedisConnectionTestResult(true, mode, 2, 8, "ok");
        };
        when(clusters.findById(7)).thenReturn(Optional.of(new io.github.redisops.domain.asset.RedisCluster(
                7L, "cluster", "prod", null, "owner", null, null, ClusterMode.STANDALONE,
                "7.2", "old:6379", 1L, io.github.redisops.domain.asset.ClusterStatus.ACTIVE,
                0, Instant.now(), Instant.now())));
        when(profiles.get(7)).thenReturn(new RedisConnectionProfile(7, ClusterMode.STANDALONE,
                List.of("old:6379"), null, "old-user", "ACL", "saved-secret".toCharArray()));

        var result = new RedisConnectionTestService(clusters, profiles, port)
                .test(7L, ClusterMode.CLUSTER, "new-a:6379,new-b:6379",
                        true, "new-user", "");
        assertEquals(2, result.discoveredNodeCount());
    }
}
