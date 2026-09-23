package io.github.susongyan.redisops.platform.api.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.susongyan.redisops.platform.api.asset.ClusterController;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.asset.*;
import io.github.susongyan.redisops.platform.application.identity.LocalIdentityService;
import io.github.susongyan.redisops.platform.application.location.LocationService;
import io.github.susongyan.redisops.platform.domain.asset.*;
import jakarta.servlet.Filter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
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

class ClusterDetailPasswordTest {
    @Test
    void detailRequiresLoginAndReturnsUncachedPasswordWithoutMutationOrAuditWorkflow() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/v1/clusters/7")).andExpect(status().isUnauthorized());
            for (String role : new String[]{"ADMIN", "OPERATOR"}) {
                var session = new MockHttpSession();
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        new SecurityContextImpl(new UsernamePasswordAuthenticationToken("tester", null,
                                AuthorityUtils.createAuthorityList("ROLE_" + role))));
                mvc.perform(get("/api/v1/clusters/7").session(session)).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.password").value("test-only-secret"));
            }
            assertFalse(new ClusterController.ClusterDetail(null, null, null, null, null,
                    "test-only-secret").toString().contains("test-only-secret"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {
        @Bean
        ClusterController controller() {
            var clusters = mock(ClusterService.class);
            when(clusters.get(7)).thenReturn(new RedisCluster(7L, "test", "test", null, "owner", null, null,
                    ClusterMode.STANDALONE, "7.2", "redis:6379", null, ClusterStatus.ACTIVE,
                    0, Instant.EPOCH, Instant.EPOCH));
            var passwords = mock(ClusterPasswordService.class);
            when(passwords.read(7)).thenReturn("test-only-secret");
            return new ClusterController(clusters, mock(AssetService.class), mock(IdempotencyService.class),
                    mock(LocationService.class), mock(RedisConnectionTestService.class), passwords);
        }
        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new IdentitySecurity().identityChain(http, mock(LocalIdentityService.class),
                    new HttpSessionSecurityContextRepository());
        }
    }
}
