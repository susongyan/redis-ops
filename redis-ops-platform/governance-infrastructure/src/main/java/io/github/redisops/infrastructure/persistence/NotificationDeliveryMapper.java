package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.alert.NotificationDelivery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationDeliveryMapper {
    @Insert("INSERT INTO notification_record(channel_id,event_id,status,next_attempt_at) VALUES(#{channelId},#{eventId},'PENDING',CURRENT_TIMESTAMP(3))")
    void enqueue(@Param("channelId") long channelId, @Param("eventId") long eventId);
    @Select("SELECT id,channel_id channelId,event_id alertEventId,attempt attemptCount,next_attempt_at nextAttemptAt,status,last_error lastError,created_at createdAt,created_at updatedAt FROM notification_record WHERE status IN ('PENDING','RETRYING') AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY id LIMIT #{limit}")
    List<NotificationDelivery> due(int limit);
    @Update("UPDATE notification_record SET status='SENT',attempt=attempt+1,last_error=NULL,delivered_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status IN ('PENDING','RETRYING')")
    int sent(long id);
    @Update("UPDATE notification_record SET status='RETRYING',attempt=#{attempt},next_attempt_at=#{next},last_error=#{error} WHERE id=#{id} AND status IN ('PENDING','RETRYING')")
    int retry(@Param("id") long id, @Param("attempt") int attempt, @Param("next") Instant next,
            @Param("error") String error);
    @Select("SELECT id,channel_id channelId,event_id alertEventId,attempt attemptCount,next_attempt_at nextAttemptAt,status,last_error lastError,created_at createdAt,created_at updatedAt FROM notification_record ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<NotificationDelivery> history(@Param("offset") int offset, @Param("size") int size);
    @Select("SELECT COUNT(*) FROM notification_record")
    long count();
}
