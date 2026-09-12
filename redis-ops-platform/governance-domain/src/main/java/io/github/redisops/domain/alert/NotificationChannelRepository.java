package io.github.redisops.domain.alert;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelRepository {
    NotificationChannel save(NotificationChannel channel, byte[] encryptedConfig, String keyId);
    Optional<NotificationChannel> find(long id);
    Optional<EncryptedNotificationChannel> findEncrypted(long id);
    List<NotificationChannel> list();
    boolean update(NotificationChannel channel, byte[] encryptedConfig, String keyId);
}
