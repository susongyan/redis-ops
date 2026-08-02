package io.github.redisops.domain.alert;

public record EncryptedNotificationChannel(NotificationChannel channel, byte[] encryptedConfig, String keyId) {
}
