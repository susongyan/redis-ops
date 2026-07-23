package io.github.redisops.worker;

import io.github.redisops.application.asset.AssetService;
import io.github.redisops.domain.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DiscoveryJobWorkerConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WorkerTestConfiguration.class);

    @Test void workerIsEnabledByDefault() {
        context.run(result -> assertThat(result).hasSingleBean(DiscoveryJobWorker.class));
    }

    @Test void workerCanBeDisabledForPureApiInstance() {
        context.withPropertyValues("worker.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(DiscoveryJobWorker.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DiscoveryJobWorker.class)
    static class WorkerTestConfiguration {
        @Bean JobRepository jobs() { return mock(JobRepository.class); }
        @Bean AssetService assets() { return mock(AssetService.class); }
    }
}
