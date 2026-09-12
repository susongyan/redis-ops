package io.github.redisops.platform.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.redisops.platform.api.analysis.AnalysisController;
import io.github.redisops.platform.api.analysis.AnalysisAgentProfileController;
import io.github.redisops.platform.application.analysis.AnalysisService;
import io.github.redisops.platform.application.analysis.AnalysisAgentProfileService;
import io.github.redisops.platform.application.IdempotencyService;
import io.github.redisops.platform.infrastructure.analysis.AcpAnalysisAgentClient;
import io.github.redisops.platform.infrastructure.analysis.DemoAcpAnalysisAgentClient;
import io.github.redisops.platform.infrastructure.analysis.HttpJsonAnalysisAgentClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AnalysisConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(HttpJsonAnalysisAgentClient.class, AcpAnalysisAgentClient.class,
                    AnalysisController.class, AnalysisAgentProfileController.class)
            .withBean(AnalysisService.class, () -> mock(AnalysisService.class))
            .withBean(AnalysisAgentProfileService.class, () -> mock(AnalysisAgentProfileService.class))
            .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void apiAlwaysAvailableAndUnconfiguredAdaptersDoNotMatch() {
        context.run(c -> {
            assertThat(c).hasSingleBean(AnalysisController.class).hasSingleBean(AnalysisAgentProfileController.class);
            assertThat(c.getBean(HttpJsonAnalysisAgentClient.class).supportedTypes()).isEmpty();
            assertThat(c.getBean(AcpAnalysisAgentClient.class).supportedTypes()).isEmpty();
            assertThat(c).doesNotHaveBean(DemoAcpAnalysisAgentClient.class);
        });
    }

    @Test
    void endpointConfigurationNeedsNoSwitchAndIgnoresLegacySwitches() {
        context.withPropertyValues("redis-ops.analysis.enabled=false", "redis-ops.analysis.http.enabled=false",
                "redis-ops.analysis.acp.enabled=false", "redis-ops.analysis.http.endpoint=http://localhost:9999",
                "redis-ops.analysis.acp.endpoint=http://localhost:9998").run(c -> {
                    assertThat(c).hasSingleBean(AnalysisController.class)
                            .hasSingleBean(AnalysisAgentProfileController.class);
                    assertThat(c.getBean(HttpJsonAnalysisAgentClient.class).supportedTypes()).isNotEmpty();
                    assertThat(c.getBean(AcpAnalysisAgentClient.class).supportedTypes()).isNotEmpty();
                });
    }
}
