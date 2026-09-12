package io.github.redisops.platform.scheduling;

import io.github.redisops.platform.application.alert.AlertService;
import io.github.redisops.platform.domain.asset.ClusterRepository;
import io.github.redisops.platform.domain.asset.RedisConnectionProfileProvider;
import io.github.redisops.platform.domain.collector.CollectorRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CollectorConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RedisCollectorWorker.class, CollectorSnapshotController.class)
            .withBean(ClusterRepository.class, () -> mock(ClusterRepository.class))
            .withBean(RedisConnectionProfileProvider.class, () -> mock(RedisConnectionProfileProvider.class))
            .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
            .withBean(AlertService.class, () -> mock(AlertService.class))
            .withBean(CollectorRunRepository.class, () -> mock(CollectorRunRepository.class));

    @Test
    void collectorIsEnabledByDefault() {
        context.run(c -> assertThat(c).hasSingleBean(RedisCollectorWorker.class));
    }

    @Test
    void disabledCollectorKeepsSnapshotApiAvailableWithoutDependencyFailure() {
        context.withPropertyValues("collector.enabled=false").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(RedisCollectorWorker.class);
            assertThat(c.getBean(CollectorSnapshotController.class).nodes(1, new MockHttpServletRequest()).data())
                    .isEmpty();
        });
    }

    @Test
    void invalidIntervalOrPageSizeFailsStartup() {
        context.withPropertyValues("collector.fast-interval-ms=0").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("collector.page-size=-1").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void scheduleResolvesConfiguredInterval() throws Exception {
        String delay = RedisCollectorWorker.class.getMethod("collect").getAnnotation(Scheduled.class)
                .fixedDelayString();
        context.withPropertyValues("collector.fast-interval-ms=30000")
                .run(c -> assertThat(c.getEnvironment().resolveRequiredPlaceholders(delay)).isEqualTo("30000"));
    }
}
