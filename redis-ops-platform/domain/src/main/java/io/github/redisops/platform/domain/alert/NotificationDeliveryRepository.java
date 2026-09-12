package io.github.redisops.platform.domain.alert;

import java.time.Instant;
import java.util.List;
import io.github.redisops.platform.common.PageResult;

public interface NotificationDeliveryRepository {
    void enqueue(long channelId, long alertEventId);
    List<NotificationDelivery> due(int limit);
    void markSent(long id);
    void retry(long id, int attempt, Instant nextAttemptAt, String error);
    PageResult<NotificationDelivery> history(int page, int size);
}
