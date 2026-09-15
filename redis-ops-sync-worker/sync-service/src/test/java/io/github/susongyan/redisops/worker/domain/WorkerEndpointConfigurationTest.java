package io.github.susongyan.redisops.worker.domain;

import io.github.susongyan.redisops.worker.runtime.WorkerClusterMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEndpointConfigurationTest {
    @Test
    void parsesStandaloneSentinelAndBracketedIpv6() {
        assertThat(WorkerEndpointConfiguration.parse(WorkerClusterMode.STANDALONE, "redis:6379").seedEndpoints())
                .containsExactly("redis:6379");
        WorkerEndpointConfiguration sentinel = WorkerEndpointConfiguration.parse(WorkerClusterMode.SENTINEL,
                "primary@one:26379,two:26379");
        assertThat(sentinel.sentinelMasterName()).isEqualTo("primary");
        assertThat(WorkerEndpointConfiguration.parse(WorkerClusterMode.CLUSTER, "[2001:db8::1]:6379")
                .seedEndpoints()).containsExactly("[2001:db8::1]:6379");
    }

    @Test
    void rejectsBareIpv6() {
        assertThatThrownBy(() -> WorkerEndpointConfiguration.parse(WorkerClusterMode.CLUSTER,
                "2001:db8::1:6379")).isInstanceOf(IllegalArgumentException.class);
    }
}
