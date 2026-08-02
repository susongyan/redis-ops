package io.github.redisops.application.alert;

import io.github.redisops.common.BusinessException;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.alert.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationChannelService {
    private final NotificationChannelRepository channels;
    private final NotificationSecretProtector secrets;
    private final NotificationDeliveryRepository deliveries;
    public NotificationChannelService(NotificationChannelRepository channels, NotificationSecretProtector secrets,
            NotificationDeliveryRepository deliveries) {
        this.channels = channels;
        this.secrets = secrets;
        this.deliveries = deliveries;
    }
    @Transactional
    public NotificationChannel create(String name, String webhookUrl) {
        if (name == null || name.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", "name is required");
        if (webhookUrl == null || !webhookUrl.matches("https?://.+"))
            throw new BusinessException("INVALID_ARGUMENT", "webhook URL must use HTTP or HTTPS");
        UUID id = UUID.randomUUID();
        ProtectedNotificationSecret protectedValue = secrets.encrypt(id, webhookUrl.toCharArray());
        return channels.save(new NotificationChannel(null, id, name, "GENERIC_WEBHOOK", "ACTIVE", true, 0,
                Instant.now(), Instant.now()), protectedValue.ciphertext(), protectedValue.keyId());
    }
    public List<NotificationChannel> list() {
        return channels.list();
    }
    @Transactional
    public NotificationChannel update(long id, long version, String name, String webhookUrl, String status) {
        var current = channels.findEncrypted(id)
                .orElseThrow(() -> BusinessException.notFound("notificationChannel", id));
        if (name == null || name.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", "name is required");
        String nextStatus = status == null || status.isBlank() ? current.channel().status() : status;
        if (!Set.of("ACTIVE", "DISABLED").contains(nextStatus))
            throw new BusinessException("INVALID_ARGUMENT", "status must be ACTIVE or DISABLED");
        byte[] ciphertext = null;
        String keyId = null;
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            if (!webhookUrl.matches("https?://.+"))
                throw new BusinessException("INVALID_ARGUMENT", "webhook URL must use HTTP or HTTPS");
            var protectedValue = secrets.encrypt(current.channel().channelUuid(), webhookUrl.toCharArray());
            ciphertext = protectedValue.ciphertext();
            keyId = protectedValue.keyId();
        }
        var next = new NotificationChannel(id, current.channel().channelUuid(), name, current.channel().type(),
                nextStatus,
                current.channel().configured(), version, current.channel().createdAt(), Instant.now());
        if (!channels.update(next, ciphertext, keyId))
            throw new BusinessException("VERSION_CONFLICT", "notification channel changed");
        return channels.find(id).orElseThrow();
    }
    public EncryptedNotificationChannel encrypted(long id) {
        return channels.findEncrypted(id).orElseThrow(() -> BusinessException.notFound("notificationChannel", id));
    }
    public char[] decrypt(EncryptedNotificationChannel channel) {
        return secrets.decrypt(channel.channel().channelUuid(), channel.encryptedConfig(), channel.keyId());
    }
    public PageResult<NotificationDelivery> history(int page, int size) {
        return deliveries.history(page, size);
    }
}
