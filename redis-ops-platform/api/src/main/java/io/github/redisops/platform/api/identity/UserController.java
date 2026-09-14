package io.github.redisops.platform.api.identity;

import io.github.redisops.platform.application.identity.UserAdministrationService;
import io.github.redisops.platform.domain.identity.PlatformUser;
import io.github.redisops.platform.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserAdministrationService service;
    public UserController(UserAdministrationService service) {
        this.service = service;
    }
    public static final class Create {
        public String login;
        public String displayName;
        public PlatformUser.Role role;
        public char[] password;
    }
    public record Update(String displayName, PlatformUser.Status status, PlatformUser.Role role) {
    }
    public static final class Reset {
        public char[] password;
    }
    @GetMapping
    Object list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(service.list(page, size), null);
    }
    @PostMapping
    Object create(@RequestHeader("Idempotency-Key") String key, @RequestBody Create body, HttpServletRequest request) {
        try {
            return ApiResponse.of(service.create(AuthenticationController.id(request), key, body.login,
                    body.displayName, body.role, body.password), null);
        } finally {
            if (body.password != null)
                Arrays.fill(body.password, '\0');
        }
    }
    @PatchMapping("/{id}")
    Object update(@PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Update body, HttpServletRequest request) {
        return ApiResponse.of(service.update(AuthenticationController.id(request), key, id, version, body.displayName(),
                body.status(), body.role()), null);
    }
    @PostMapping("/{id}/reset-password")
    Object reset(@PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Reset body, HttpServletRequest request) {
        try {
            return ApiResponse.of(service.reset(AuthenticationController.id(request), key, id, version, body.password),
                    null);
        } finally {
            if (body.password != null)
                Arrays.fill(body.password, '\0');
        }
    }
}
