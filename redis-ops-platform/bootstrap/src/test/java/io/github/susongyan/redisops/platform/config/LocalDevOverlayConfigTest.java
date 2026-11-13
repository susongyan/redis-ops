package io.github.susongyan.redisops.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The packaged {@code application-dev.yml} is the only local-dev overlay. It
 * must stay inert without the dev profile (deployments are profile-less and
 * runtime migration stays off), and once activated it flips exactly the two
 * local defaults: fresh databases migrate themselves and plain-HTTP cookies
 * are allowed.
 */
class LocalDevOverlayConfigTest {

    @Configuration(proxyBeanMethods = false)
    static class Bare {

    }

    @Test
    void devOverlayEnablesLocalFlywayAndPlainHttpCookies() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=dev")) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).contains("dev");
            assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("identity.cookie-secure")).isEqualTo("false");
        }
    }

    @Test
    void withoutDevProfileMigrationStaysOffAndCookiesStaySecure() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .run()) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("identity.cookie-secure")).isEqualTo("true");
        }
    }
}
