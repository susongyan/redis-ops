package io.github.susongyan.redisops.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The packaged {@code application-dev.yml} is an intentionally empty marker:
 * local development needs no overlay properties (base defaults already target
 * the local compose MySQL). The guard locks the one real contract — the
 * worker never runs Flyway under any profile, because the database schema is
 * owned by the Platform.
 */
class LocalDevOverlayConfigTest {

    @Configuration(proxyBeanMethods = false)
    static class Bare {

    }

    @Test
    void devOverlayStaysAMarkerAndNeverEnablesWorkerFlyway() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=dev")) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).contains("dev");
            assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        }
    }

    @Test
    void profileLessStartupKeepsWorkerFlywayOff() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
        }
    }
}
