package io.github.redisops.domain.alert;

public record ProtectedNotificationSecret(byte[] ciphertext, String keyId) {
}
