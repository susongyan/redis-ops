package io.github.susongyan.redisops.platform.api.identity;

import io.github.susongyan.redisops.platform.application.identity.LocalIdentityService;
import io.github.susongyan.redisops.platform.domain.identity.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
public class IdentitySecurity {
    static final String USER_ID = "platform.userId", AUTH_VERSION = "platform.authorizationVersion",
            LOGIN = "platform.login", START = "platform.sessionStart";
    @Bean
    PasswordHashPort passwordHashPort() {
        var encoder = new BCryptPasswordEncoder(12);
        return new PasswordHashPort() {
            public String hash(char[] password) {
                LocalPasswordPolicy.validate(password);
                return encoder.encode(java.nio.CharBuffer.wrap(password));
            }
            public boolean matches(char[] password, String hash) {
                if (password == null || password.length > 72)
                    return false;
                try {
                    LocalPasswordPolicy.validate(password);
                    return encoder.matches(java.nio.CharBuffer.wrap(password), hash);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
        };
    }
    @Bean
    SecurityContextRepository contextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${identity.cookie-secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("REDIS_OPS_SESSION");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        cookie.setCookiePath("/");
        return cookie;
    }
    @Bean
    @DependsOnDatabaseInitialization
    @ConditionalOnProperty(name = "identity.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner initialize(LocalIdentityService users,
            @Value("${identity.bootstrap.username:admin}") String login,
            @Value("${identity.bootstrap.password:}") String password) {
        return args -> {
            char[] value = password.toCharArray();
            try {
                users.initialize(login, value);
            } finally {
                java.util.Arrays.fill(value, '\0');
            }
        };
    }
    @Bean
    SecurityFilterChain identityChain(HttpSecurity http, LocalIdentityService users, SecurityContextRepository contexts)
            throws Exception {
        var csrf = new HttpSessionCsrfTokenRepository();
        http.csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(c -> c.securityContextRepository(contexts))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .exceptionHandling(
                        c -> c.authenticationEntryPoint((r, s, e) -> error(s, 401, "AUTHENTICATION_REQUIRED"))
                                .accessDeniedHandler((r, s, e) -> error(s, 403, "ACCESS_DENIED")))
                .authorizeHttpRequests(c -> c
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health",
                                "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
                .addFilterAfter(new SessionGuard(users), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}");
    }
    static final class SessionGuard extends OncePerRequestFilter {
        private final LocalIdentityService users;
        SessionGuard(LocalIdentityService users) {
            this.users = users;
        }
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            var session = req.getSession(false);
            if (session != null && session.getAttribute(USER_ID) instanceof Long id) {
                try {
                    var user = users.get(id);
                    long started = (Long) session.getAttribute(START),
                            version = (Long) session.getAttribute(AUTH_VERSION);
                    if (user.status() != PlatformUser.Status.ACTIVE || user.authorizationVersion() != version
                            || System.currentTimeMillis() - started >= Duration.ofHours(8).toMillis()) {
                        session.invalidate();
                        SecurityContextHolder.clearContext();
                        error(res, 401, "SESSION_EXPIRED");
                        return;
                    }
                    String path = req.getRequestURI().substring(req.getContextPath().length());
                    if (user.passwordChangeRequired() && !java.util.Set
                            .of("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout", "/api/v1/auth/csrf")
                            .contains(path)) {
                        error(res, 403, "PASSWORD_CHANGE_REQUIRED");
                        return;
                    }
                } catch (Exception e) {
                    SecurityContextHolder.clearContext();
                    error(res, 503, "AUTHENTICATION_UNAVAILABLE");
                    return;
                }
            }
            chain.doFilter(req, res);
        }
    }
}
