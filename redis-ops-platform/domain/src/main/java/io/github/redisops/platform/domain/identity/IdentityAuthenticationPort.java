package io.github.redisops.platform.domain.identity;

/** Provider-specific proofs stay inside adapters; callers never use display names as identity. */
public interface IdentityAuthenticationPort<P> {
    IdentityKey.Source source();
    AuthenticatedIdentity authenticate(P proof);
}
