package io.github.redisops.platform.domain.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentityPolicyTest {
    PlatformUser user(PlatformUser.Status status, PlatformUser.Role role, boolean change) {
        return new PlatformUser(1, "Operator", status, role, change, 0, 2, Instant.EPOCH, Instant.EPOCH, null);
    }

    @Test
    void authenticationSourceCannotMergeIdentities() {
        assertEquals(new IdentityKey(IdentityKey.Source.LOCAL, "local", "Alice"),
                new IdentityKey(IdentityKey.Source.LOCAL, "local", "alice"));
        assertNotEquals(new IdentityKey(IdentityKey.Source.OIDC, "issuer", "Alice"),
                new IdentityKey(IdentityKey.Source.OIDC, "issuer", "alice"));
        assertNotEquals(new IdentityKey(IdentityKey.Source.LOCAL, "local", "alice"),
                new IdentityKey(IdentityKey.Source.LDAP, "directory", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityKey(IdentityKey.Source.LOCAL, "other", "alice"));
        assertThrows(IllegalArgumentException.class, () -> IdentityKey.normalizeLogin(" alice "));
    }

    @Test
    void onlyCurrentActiveUnrestrictedSessionsCanOperate() {
        assertTrue(user(PlatformUser.Status.ACTIVE, PlatformUser.Role.OPERATOR, false).permitsBusiness(2));
        assertFalse(user(PlatformUser.Status.ACTIVE, PlatformUser.Role.OPERATOR, false).permitsUserManagement(2));
        assertTrue(user(PlatformUser.Status.ACTIVE, PlatformUser.Role.ADMIN, false).permitsUserManagement(2));
        assertFalse(user(PlatformUser.Status.ACTIVE, PlatformUser.Role.ADMIN, false).permitsBusiness(1));
        assertFalse(user(PlatformUser.Status.ACTIVE, PlatformUser.Role.ADMIN, true).permitsBusiness(2));
        assertFalse(user(PlatformUser.Status.DISABLED, PlatformUser.Role.ADMIN, false).permitsBusiness(2));
        assertFalse(user(PlatformUser.Status.PENDING, null, false).permitsBusiness(2));
    }
}
