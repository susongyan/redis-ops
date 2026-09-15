package io.github.susongyan.redisops.platform.domain.identity;

import java.util.Objects;

/** Authentication evidence only: the adapter cannot grant application roles or active status. */
public record AuthenticatedIdentity(IdentityKey identity, String displayName) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(identity, "identity");
        if (displayName == null || displayName.isBlank() || displayName.length() > 128)
            throw new IllegalArgumentException("INVALID_DISPLAY_NAME");
    }
}
