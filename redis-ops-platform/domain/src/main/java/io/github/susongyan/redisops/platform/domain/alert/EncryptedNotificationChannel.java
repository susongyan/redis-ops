package io.github.susongyan.redisops.platform.domain.alert;

public record EncryptedNotificationChannel(NotificationChannel channel, byte[] encryptedConfig, String keyId) {
}
