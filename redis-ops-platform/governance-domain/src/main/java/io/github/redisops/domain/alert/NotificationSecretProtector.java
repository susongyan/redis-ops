package io.github.redisops.domain.alert;

import java.util.UUID;

public interface NotificationSecretProtector {
    ProtectedNotificationSecret encrypt(UUID channelUuid, char[] value);
    char[] decrypt(UUID channelUuid, byte[] ciphertext, String keyId);
}
