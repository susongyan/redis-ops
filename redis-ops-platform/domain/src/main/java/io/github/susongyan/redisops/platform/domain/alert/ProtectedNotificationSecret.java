package io.github.susongyan.redisops.platform.domain.alert;

public record ProtectedNotificationSecret(byte[] ciphertext, String keyId) {
}
