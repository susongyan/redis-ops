package io.github.susongyan.redisops.platform.domain.identity;

/** Internal credential only; never use this type as an HTTP response or audit payload. */
public final class LocalCredential {
    private final long userId;
    private final String hash;
    public LocalCredential(long userId, String hash) {
        this.userId = userId;
        this.hash = hash;
    }
    public long userId() {
        return userId;
    }
    public String hash() {
        return hash;
    }
    @Override
    public String toString() {
        return "LocalCredential[redacted]";
    }
}
