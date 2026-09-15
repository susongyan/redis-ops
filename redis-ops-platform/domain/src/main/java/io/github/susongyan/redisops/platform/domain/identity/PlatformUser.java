package io.github.susongyan.redisops.platform.domain.identity;

import java.time.Instant;
import java.util.Objects;

/** Public user state; deliberately contains neither credentials nor external authentication tokens. */
public record PlatformUser(long id, String displayName, Status status, Role role,
        boolean passwordChangeRequired, long version, long authorizationVersion,
        Instant createdAt, Instant updatedAt, Instant lastLoginAt) {
    public enum Status {
        PENDING, ACTIVE, DISABLED
    }
    public enum Role {
        ADMIN, OPERATOR
    }

    public PlatformUser {
        if (id < 1 || displayName == null || displayName.isBlank() || displayName.length() > 128
                || version < 0 || authorizationVersion < 0)
            throw new IllegalArgumentException("INVALID_USER");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == Status.ACTIVE && role == null || status == Status.PENDING && role != null)
            throw new IllegalArgumentException("INVALID_USER_ROLE");
    }

    public boolean permitsBusiness(long sessionAuthorizationVersion) {
        return status == Status.ACTIVE && role != null && !passwordChangeRequired
                && authorizationVersion == sessionAuthorizationVersion;
    }

    public boolean permitsUserManagement(long sessionAuthorizationVersion) {
        return permitsBusiness(sessionAuthorizationVersion) && role == Role.ADMIN;
    }
}
