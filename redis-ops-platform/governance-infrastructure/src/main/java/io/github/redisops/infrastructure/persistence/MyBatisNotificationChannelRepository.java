package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.alert.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisNotificationChannelRepository implements NotificationChannelRepository {
    private final NotificationChannelMapper mapper;
    public MyBatisNotificationChannelRepository(NotificationChannelMapper mapper) {
        this.mapper = mapper;
    }
    public NotificationChannel save(NotificationChannel channel, byte[] encryptedConfig, String keyId) {
        var row = new NotificationChannelMapper.Row();
        row.uuid = channel.channelUuid().toString();
        row.name = channel.name();
        row.ciphertext = encryptedConfig;
        row.keyId = keyId;
        mapper.insert(row);
        return mapper.find(row.id);
    }
    public Optional<NotificationChannel> find(long id) {
        return Optional.ofNullable(mapper.find(id));
    }
    public List<NotificationChannel> list() {
        return mapper.list();
    }
    public boolean update(NotificationChannel channel, byte[] encryptedConfig, String keyId) {
        var row = new NotificationChannelMapper.Row();
        row.id = channel.id();
        row.name = channel.name();
        row.status = channel.status();
        row.version = channel.version();
        row.ciphertext = encryptedConfig;
        row.keyId = keyId;
        return mapper.update(row) == 1;
    }
    public Optional<EncryptedNotificationChannel> findEncrypted(long id) {
        var row = mapper.encrypted(id);
        if (row == null)
            return Optional.empty();
        var channel = new NotificationChannel(row.id, UUID.fromString(row.channelUuid), row.name, row.type, row.status,
                row.configured,
                row.version, row.createdAt, row.updatedAt);
        return Optional.of(new EncryptedNotificationChannel(channel, row.ciphertext, row.keyId));
    }
}
