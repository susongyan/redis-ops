package io.github.susongyan.redisops.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Apollo is an optional integration: the default {@code spring.config.import}
 * carries {@code optional:apollo://application}, so the platform started
 * without any Apollo meta server or app.id must still build its context from
 * the classpath defaults.
 */
class ApolloOptionalConfigImportTest {

    @Configuration(proxyBeanMethods = false)
    static class Bare {

    }

    @Test
    void startupToleratesMissingApollo() {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .run()) {
            // Context started: config-data resolution skipped the absent Apollo source.
        }
    }
}
