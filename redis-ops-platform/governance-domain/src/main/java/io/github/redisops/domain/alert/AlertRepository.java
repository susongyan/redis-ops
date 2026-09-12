package io.github.redisops.domain.alert;
import io.github.redisops.common.PageResult;
import java.time.Instant;
import java.util.*;
public interface AlertRepository {
    AlertRule saveRule(AlertRule rule);
    Optional<AlertRule> findRule(long id);
    List<AlertRule> rules();
    boolean updateRule(AlertRule rule, long version);
    AlertEvent upsertOpen(long ruleId, String resourceType, String resourceId, AlertSeverity severity, String title,
            String evidenceJson);
    Optional<AlertEvent> findEvent(long id);
    PageResult<AlertEvent> events(AlertStatus status, int page, int size);
    boolean acknowledge(long id, String operator, long version);
    boolean resolve(long id, long version);
    boolean silence(long id, Instant until, long version);
}
