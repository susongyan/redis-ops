package io.github.susongyan.redisops.platform.domain.identity;

import java.util.Locale;
import java.util.Objects;

/** Immutable identity binding. External identifiers must never be case-folded or email-linked. */
public record IdentityKey(Source source, String provider, String subject) {
    public enum Source {
        LOCAL, OIDC, LDAP
    }

    public IdentityKey {
        Objects.requireNonNull(source, "source");
        if (provider == null || provider.isBlank() || provider.length() > 512
                || subject == null || subject.isBlank() || subject.length() > 512)
            throw new IllegalArgumentException("INVALID_IDENTITY");
        if (source == Source.LOCAL) {
            if (!"local".equals(provider))
                throw new IllegalArgumentException("INVALID_LOCAL_PROVIDER");
            subject = normalizeLogin(subject);
        }
    }

    public static String normalizeLogin(String login) {
        if (login == null || !login.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
            throw new IllegalArgumentException("INVALID_LOGIN_NAME");
        return login.toLowerCase(Locale.ROOT);
    }
}
