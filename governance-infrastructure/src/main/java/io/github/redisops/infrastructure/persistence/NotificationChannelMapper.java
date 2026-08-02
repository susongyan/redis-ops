package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.alert.NotificationChannel;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationChannelMapper {
    @Insert("INSERT INTO notification_channel(channel_uuid,name,channel_type,status,encrypted_config,key_id,version) VALUES(#{uuid},#{name},'GENERIC_WEBHOOK','ACTIVE',#{ciphertext},#{keyId},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Row row);

    @Select("SELECT id,channel_uuid channelUuid,name,channel_type type,status,(encrypted_config IS NOT NULL) configured,version,created_at createdAt,updated_at updatedAt FROM notification_channel WHERE id=#{id}")
    NotificationChannel find(long id);

    @Select("SELECT id,channel_uuid channelUuid,name,channel_type type,status,(encrypted_config IS NOT NULL) configured,version,created_at createdAt,updated_at updatedAt FROM notification_channel ORDER BY id DESC")
    List<NotificationChannel> list();

    @Update("<script>UPDATE notification_channel SET name=#{name}, status=#{status}, version=version+1"
            + "<if test='ciphertext != null'>, encrypted_config=#{ciphertext}, key_id=#{keyId}</if>"
            + " WHERE id=#{id} AND version=#{version} AND status != 'DELETED'</script>")
    int update(Row row);

    @Select("SELECT id,channel_uuid channelUuid,name,channel_type type,status,(encrypted_config IS NOT NULL) configured,version,created_at createdAt,updated_at updatedAt,encrypted_config ciphertext,key_id keyId FROM notification_channel WHERE id=#{id}")
    EncryptedRow encrypted(long id);

    class Row {
        public Long id;
        public String uuid;
        public String name;
        public byte[] ciphertext;
        public String keyId;
        public String status;
        public long version;
    }
    class EncryptedRow {
        public Long id;
        public String channelUuid;
        public String name, type, status, keyId;
        public boolean configured;
        public long version;
        public Instant createdAt, updatedAt;
        public byte[] ciphertext;
    }
}
