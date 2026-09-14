package io.github.redisops.platform.domain.identity;

public interface PasswordHashPort {
    String hash(char[] password);
    boolean matches(char[] password, String hash);
}
