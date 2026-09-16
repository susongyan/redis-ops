package io.github.susongyan.redisops.platform.application.identity;
import io.github.susongyan.redisops.platform.application.audit.AuditDetails;
import java.util.Map;

import io.github.susongyan.redisops.platform.domain.identity.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AuditRepository audit;
    private final IdempotencyService idem;
    public UserAdministrationService(UserRepository users, PasswordHashPort passwords, AuditRepository audit,
            IdempotencyService idem) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.idem = idem;
    }
    public PageResult<UserRepository.Account> list(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100)
            throw new IllegalArgumentException("INVALID_PAGE");
        return users.list(page, size);
    }
    private void admin(long actor) {
        var user = users.get(actor);
        if (!user.permitsUserManagement(user.authorizationVersion()))
            throw new BusinessException("ACCESS_DENIED", "Administrator required");
    }
    private static void name(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("INVALID_DISPLAY_NAME");
    }
    @Transactional
    public PlatformUser create(long actor, String key, String login, String displayName, PlatformUser.Role role,
            char[] password) {
        users.lockAdministration();
        admin(actor);
        name(displayName);
        if (role == null)
            throw new IllegalArgumentException("INVALID_ROLE");
        String normalized = IdentityKey.normalizeLogin(login);
        LocalPasswordPolicy.validate(password);
        // Only non-secret metadata enters the general idempotency digest. First successful password wins.
        return idem.execute("user:" + actor, key, "USER_CREATE", java.util.List.of(normalized, displayName, role),
                () -> {
                    var created = users.createLocal(normalized, displayName, passwords.hash(password), role);
                    audit.append("user:" + actor, "USER_CREATED", "USER", Long.toString(created.id()), "SUCCESS",
                            AuditDetails.change("新增用户：" + normalized, Map.of(), userDetails(created), null));
                    return created;
                }, u -> Long.toString(u.id()), id -> users.get(Long.parseLong(id)));
    }
    @Transactional
    public PlatformUser update(long actor, String key, long id, long version, String displayName,
            PlatformUser.Status status, PlatformUser.Role role) {
        users.lockAdministration();
        admin(actor);
        name(displayName);
        if (status == null || status == PlatformUser.Status.PENDING || role == null)
            throw new IllegalArgumentException("INVALID_USER_STATE");
        return idem.execute("user:" + actor, key, "USER_UPDATE",
                java.util.List.of(id, version, displayName, status, role), () -> {
                    var previous = users.get(id);
                    users.update(id, displayName, status, role, version);
                    audit.append("user:" + actor, "USER_UPDATED", "USER", Long.toString(id), "SUCCESS",
                            AuditDetails.change("修改用户：" + displayName, userDetails(previous),
                                    userDetails(users.get(id)), null));
                    return users.get(id);
                }, u -> Long.toString(u.id()), value -> users.get(Long.parseLong(value)));
    }
    @Transactional
    public PlatformUser reset(long actor, String key, long id, long version, char[] password) {
        users.lockAdministration();
        admin(actor);
        LocalPasswordPolicy.validate(password);
        return idem.execute("user:" + actor, key, "USER_PASSWORD_RESET", java.util.List.of(id, version), () -> {
            users.resetPassword(id, passwords.hash(password), version);
            audit.append("user:" + actor, "PASSWORD_RESET", "USER", Long.toString(id), "SUCCESS");
            return users.get(id);
        }, u -> Long.toString(u.id()), value -> users.get(Long.parseLong(value)));
    }
    private static Map<String, Object> userDetails(PlatformUser user) {
        return AuditDetails.fields("显示名称", user.displayName(), "角色", user.role(), "状态", user.status());
    }
}
