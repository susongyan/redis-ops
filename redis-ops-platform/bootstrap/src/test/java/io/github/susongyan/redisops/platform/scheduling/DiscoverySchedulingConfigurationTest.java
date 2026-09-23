package io.github.susongyan.redisops.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.susongyan.redisops.platform.application.asset.AssetService;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class DiscoverySchedulingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withBean(JobRepository.class, () -> mock(JobRepository.class))
            .withBean(AssetService.class, () -> mock(AssetService.class))
            .withPropertyValues("platform.jobs.discovery.poll-interval-ms=60000",
                    "platform.jobs.discovery.poll-jitter-ms=500", "spring.task.scheduling.pool.size=4",
                    "spring.task.scheduling.thread-name-prefix=platform-scheduler-");

    @Test
    void retainsBootSchedulerPoolConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DiscoveryJobWorker.class);
            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("platform-scheduler-");
        });
    }

    @Test
    void disabledJobsDoNotRegisterDiscoveryWorker() {
        runner.withPropertyValues("platform.jobs.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DiscoveryJobWorker.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(DiscoveryJobWorker.class)
    static class Config {
    }
}
