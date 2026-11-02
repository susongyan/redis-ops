package io.github.susongyan.redisops.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.susongyan.redisops.platform.application.asset.AssetService;
import io.github.susongyan.redisops.platform.application.risk.RiskScanService;
import io.github.susongyan.redisops.platform.application.validation.ValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class LocalRunRecoverySchedulingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withBean(ValidationService.class, () -> mock(ValidationService.class))
            .withBean(RiskScanService.class, () -> mock(RiskScanService.class))
            .withBean(AssetService.class, () -> mock(AssetService.class))
            .withBean(io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService.class,
                    () -> mock(io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService.class))
            .withBean(io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService.class,
                    () -> mock(
                            io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService.class))
            .withPropertyValues("platform.jobs.discovery.poll-interval-ms=60000",
                    "platform.jobs.discovery.poll-jitter-ms=500", "spring.task.scheduling.pool.size=4",
                    "spring.task.scheduling.thread-name-prefix=platform-scheduler-");

    @Test
    void retainsBootSchedulerPoolConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LocalRunRecoveryWorker.class);
            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("platform-scheduler-");
        });
    }

    @Test
    void disabledJobsDoNotRegisterDiscoveryWorker() {
        runner.withPropertyValues("platform.jobs.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalRunRecoveryWorker.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(LocalRunRecoveryWorker.class)
    static class Config {
    }
}
