package io.github.redisops.platform.domain.identity;

import java.util.Optional;

public interface UserRepository {
    record Account(PlatformUser user, String login, String source) {
    }
    io.github.redisops.platform.common.PageResult<Account> list(int page, int size);
    void update(long id, String displayName, PlatformUser.Status status, PlatformUser.Role role, long version);
    void resetPassword(long id, String hash, long version);
    PlatformUser get(long id);
    Optional<LocalCredential> credential(String normalizedLogin);
    void lockAdministration();
    boolean initialized();
    void markInitialized();
    PlatformUser createLocal(String login, String displayName, String hash, PlatformUser.Role role);
    void changePassword(long id, String hash, long expectedVersion);
    void recordLogin(long id);
    /** Atomic fixed-window limiter. Attempts are reserved before password verification. */
    boolean reserveAttempt(String opaqueKey, int limit, int windowSeconds);
    void clearAttempts(String opaqueKey);
    void cleanupAttempts();
}
