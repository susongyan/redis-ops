package io.github.susongyan.redisops.platform.api.identity;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.susongyan.redisops.platform.application.identity.LocalIdentityService;
import io.github.susongyan.redisops.platform.scheduling.CollectorSnapshotController;
import io.github.susongyan.redisops.platform.scheduling.RedisCollectorWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class CollectorMetricsSecurityTest {
    @Test
    void requiresLoginButDoesNotOpenActuator() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/v1/collector/metrics")).andExpect(status().isUnauthorized());
            for (String role : new String[]{"ADMIN", "OPERATOR"}) {
                var session = new MockHttpSession();
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        new SecurityContextImpl(new UsernamePasswordAuthenticationToken("test", null,
                                AuthorityUtils.createAuthorityList("ROLE_" + role))));
                mvc.perform(get("/api/v1/collector/metrics").session(session)).andExpect(status().isOk());
                mvc.perform(get("/actuator/prometheus").session(session)).andExpect(status().isForbidden());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {
        @Bean
        MeterRegistry meters() {
            return new SimpleMeterRegistry();
        }
        @Bean
        CollectorSnapshotController controller(ObjectProvider<RedisCollectorWorker> provider, MeterRegistry meters) {
            return new CollectorSnapshotController(provider, meters);
        }
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new IdentitySecurity().identityChain(http, mock(LocalIdentityService.class),
                    new HttpSessionSecurityContextRepository());
        }
    }
}
