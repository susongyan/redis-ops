package io.github.susongyan.redisops.platform.scheduling;

import io.github.susongyan.redisops.platform.application.asset.AssetService;
import io.github.susongyan.redisops.platform.application.risk.RiskScanService;
import io.github.susongyan.redisops.platform.application.validation.ValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalRunRecoveryWorkerConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WorkerTestConfiguration.class);

    @Test
    void workerIsEnabledByDefault() {
        context.run(result -> assertThat(result).hasSingleBean(LocalRunRecoveryWorker.class));
    }

    @Test
    void workerCanBeDisabledForPureApiInstance() {
        context.withPropertyValues("platform.jobs.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(LocalRunRecoveryWorker.class));
    }

    @Test
    void allPlatformJobsUseTheSameNewSwitch() {
        for (Class<?> type : new Class<?>[]{LocalRunRecoveryWorker.class,
                io.github.susongyan.redisops.platform.infrastructure.redis.CredentialReencryptionService.class}) {
            var condition = type.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
            assertThat(condition.name()).containsExactly("platform.jobs.enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LocalRunRecoveryWorker.class)
    static class WorkerTestConfiguration {
        @Bean
        ValidationService validations() {
            return mock(ValidationService.class);
        }
        @Bean
        RiskScanService scans() {
            return mock(RiskScanService.class);
        }
        @Bean
        AssetService assets() {
            return mock(AssetService.class);
        }
        @Bean
        io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService ttlGovernance() {
            return mock(io.github.susongyan.redisops.platform.application.governance.TtlGovernanceService.class);
        }
        @Bean
        io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService cleanupGovernance() {
            return mock(io.github.susongyan.redisops.platform.application.governance.CleanupGovernanceService.class);
        }
    }
}
