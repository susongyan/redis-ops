package io.github.susongyan.redisops.platform.api.identity;

import io.github.susongyan.redisops.platform.api.ApiResponse;
import io.github.susongyan.redisops.platform.application.identity.LocalIdentityService;
import io.github.susongyan.redisops.platform.domain.identity.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import jakarta.servlet.http.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.scheduling.annotation.Scheduled;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final LocalIdentityService service;
    private final UserRepository users;
    private final SecurityContextRepository contexts;
    private final AuditRepository audit;
    public AuthenticationController(LocalIdentityService service, UserRepository users,
            SecurityContextRepository contexts, AuditRepository audit) {
        this.service = service;
        this.users = users;
        this.contexts = contexts;
        this.audit = audit;
    }
    /** Not a record: no generated toString exposing passwords. */
    public static final class LoginBody {
        public String username;
        public char[] password;
    }
    public static final class PasswordBody {
        public char[] oldPassword;
        public char[] newPassword;
    }
    private static <T> ApiResponse<T> result(T value) {
        return ApiResponse.of(value, null);
    }
    @GetMapping("/csrf")
    Object csrf(CsrfToken token) {
        return result(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }
    @GetMapping("/me")
    Object me(HttpServletRequest request) {
        return result(service.get(id(request)));
    }
    @PostMapping("/login")
    Object login(@RequestBody LoginBody body, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        try {
            String login;
            try {
                login = IdentityKey.normalizeLogin(body.username);
            } catch (IllegalArgumentException e) {
                login = "invalid";
            }
            String account = hash("account:" + login), ip = hash("ip:" + request.getRemoteAddr());
            if (!users.reserveAttempt(ip, 30, 300) || !users.reserveAttempt(account, 5, 300)) {
                IdentitySecurity.error(response, 429, "LOGIN_RATE_LIMITED");
                return null;
            }
            PlatformUser user;
            try {
                user = service.authenticate(body.username, body.password);
            } catch (IllegalArgumentException e) {
                audit.append("anonymous", "LOGIN_FAILED", "AUTH", "local", "DENIED");
                IdentitySecurity.error(response, 401, "INVALID_CREDENTIALS");
                return null;
            }
            users.clearAttempts(account);
            var old = request.getSession(false);
            if (old != null)
                old.invalidate();
            var session = request.getSession(true);
            session.setAttribute(IdentitySecurity.USER_ID, user.id());
            session.setAttribute(IdentitySecurity.AUTH_VERSION, user.authorizationVersion());
            session.setAttribute(IdentitySecurity.LOGIN, login);
            session.setAttribute(IdentitySecurity.START, System.currentTimeMillis());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user:" + user.id(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return result(user);
        } finally {
            if (body.password != null)
                Arrays.fill(body.password, '\0');
        }
    }
    @PostMapping("/password")
    Object password(@RequestBody PasswordBody body, @RequestHeader("If-Match") long version,
            HttpServletRequest request) {
        try {
            service.changePassword(id(request), (String) request.getSession(false).getAttribute(IdentitySecurity.LOGIN),
                    body.oldPassword, body.newPassword, version);
            request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            return result(Map.of("loginRequired", true));
        } finally {
            if (body.oldPassword != null)
                Arrays.fill(body.oldPassword, '\0');
            if (body.newPassword != null)
                Arrays.fill(body.newPassword, '\0');
        }
    }
    @PostMapping("/logout")
    Object logout(HttpServletRequest request) {
        long id = id(request);
        audit.append("user:" + id, "LOGOUT", "USER", Long.toString(id), "SUCCESS");
        request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        return result(Map.of("loggedOut", true));
    }
    static long id(HttpServletRequest request) {
        return (Long) request.getSession(false).getAttribute(IdentitySecurity.USER_ID);
    }
    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE");
        }
    }
    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        users.cleanupAttempts();
    }
}
