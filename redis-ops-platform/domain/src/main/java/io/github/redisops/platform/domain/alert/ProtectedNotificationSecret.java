package io.github.redisops.platform.domain.alert;

public record ProtectedNotificationSecret(byte[] ciphertext, String keyId) {
}
