package io.github.susongyan.redisops.platform.application.identity;

import io.github.susongyan.redisops.platform.domain.identity.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalIdentityService {
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final AuditRepository audit;
    public LocalIdentityService(UserRepository users, PasswordHashPort passwords, AuditRepository audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }
    public PlatformUser get(long id) {
        return users.get(id);
    }
    @Transactional
    public void initialize(String login, char[] password) {
        users.lockAdministration();
        if (users.initialized())
            return;
        LocalPasswordPolicy.validate(password);
        var user = users.createLocal(IdentityKey.normalizeLogin(login), "Administrator", passwords.hash(password),
                PlatformUser.Role.ADMIN);
        users.markInitialized();
        audit.append("system:bootstrap", "USER_INITIALIZED", "USER", Long.toString(user.id()), "SUCCESS");
    }
    @Transactional
    public PlatformUser authenticate(String login, char[] password) {
        var credential = users.credential(IdentityKey.normalizeLogin(login));
        // A valid constant BCrypt hash avoids the fast unknown-user path.
        String hash = credential.map(LocalCredential::hash)
                .orElse("$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW");
        boolean matches = passwords.matches(password, hash);
        if (!matches || credential.isEmpty())
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        var user = users.get(credential.get().userId());
        if (user.status() != PlatformUser.Status.ACTIVE)
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        users.recordLogin(user.id());
        audit.append("user:" + user.id(), "LOGIN", "USER", Long.toString(user.id()), "SUCCESS");
        return user;
    }
    @Transactional
    public void changePassword(long id, String login, char[] oldPassword, char[] newPassword, long version) {
        var credential = users.credential(IdentityKey.normalizeLogin(login))
                .orElseThrow(() -> new IllegalArgumentException("INVALID_CREDENTIALS"));
        if (credential.userId() != id || !passwords.matches(oldPassword, credential.hash()))
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        var user = users.get(id);
        if (user.status() != PlatformUser.Status.ACTIVE)
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        LocalPasswordPolicy.validate(newPassword);
        if (passwords.matches(newPassword, credential.hash()))
            throw new IllegalArgumentException("PASSWORD_UNCHANGED");
        users.changePassword(id, passwords.hash(newPassword), version);
        audit.append("user:" + id, "PASSWORD_CHANGED", "USER", Long.toString(id), "SUCCESS");
    }
}
