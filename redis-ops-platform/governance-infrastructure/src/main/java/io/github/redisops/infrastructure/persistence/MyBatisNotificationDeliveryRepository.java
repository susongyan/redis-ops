package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.alert.*;
import io.github.redisops.common.PageResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisNotificationDeliveryRepository implements NotificationDeliveryRepository {
    private final NotificationDeliveryMapper mapper;
    public MyBatisNotificationDeliveryRepository(NotificationDeliveryMapper mapper) {
        this.mapper = mapper;
    }
    public void enqueue(long channelId, long alertEventId) {
        mapper.enqueue(channelId, alertEventId);
    }
    public List<NotificationDelivery> due(int limit) {
        return mapper.due(limit);
    }
    public void markSent(long id) {
        mapper.sent(id);
    }
    public void retry(long id, int attempt, Instant next, String error) {
        mapper.retry(id, attempt, next, error);
    }
    public PageResult<NotificationDelivery> history(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return new PageResult<>(mapper.history((safePage - 1) * safeSize, safeSize), mapper.count(), safePage,
                safeSize);
    }
}
