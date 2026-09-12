package io.github.redisops.application.alert;
import io.github.redisops.common.*;
import io.github.redisops.domain.alert.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AlertService {
    private final AlertRepository alerts;
    private final NotificationDeliveryRepository deliveries;
    public AlertService(AlertRepository a, NotificationDeliveryRepository d) {
        alerts = a;
        deliveries = d;
    }
    @Transactional
    public AlertRule create(String name, String type, AlertSeverity severity, Double threshold, Integer duration,
            Long channel) {
        if (name == null || name.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", "name is required");
        return alerts.saveRule(new AlertRule(null, name, type == null ? "COLLECTOR_UNAVAILABLE" : type,
                severity == null ? AlertSeverity.P2 : severity, true, threshold, duration == null ? 0 : duration,
                channel, 0, Instant.now(), Instant.now()));
    }
    public List<AlertRule> rules() {
        return alerts.rules();
    }
    public AlertRule rule(long id) {
        return alerts.findRule(id).orElseThrow(() -> BusinessException.notFound("alertRule", id));
    }
    @Transactional
    public AlertRule update(long id, long version, String name, String type, AlertSeverity severity, boolean enabled,
            Double threshold, Integer duration, Long channel) {
        if (name == null || name.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", "name is required");
        AlertRule current = rule(id);
        AlertRule next = new AlertRule(id, name, type == null ? current.ruleType() : type,
                severity == null ? current.severity() : severity, enabled, threshold, duration == null ? 0 : duration,
                channel, version, current.createdAt(), Instant.now());
        if (!alerts.updateRule(next, version))
            throw new BusinessException("VERSION_CONFLICT", "alert rule changed");
        return rule(id);
    }
    public PageResult<AlertEvent> events(AlertStatus s, int p, int z) {
        return alerts.events(s, p, z);
    }
    public AlertEvent event(long id) {
        return alerts.findEvent(id).orElseThrow(() -> BusinessException.notFound("alertEvent", id));
    }
    @Transactional
    public Optional<AlertEvent> trigger(String type, String resourceType, String resourceId, Double value,
            String evidence) {
        for (AlertRule r : alerts.rules())
            if (r.enabled() && r.ruleType().equals(type) && matches(r.thresholdValue(), value)) {
                AlertEvent event = alerts.upsertOpen(r.id(), resourceType, resourceId, r.severity(), r.name(),
                        json(value, evidence));
                if (r.channelId() != null
                        && (event.silenceUntil() == null || !event.silenceUntil().isAfter(Instant.now())))
                    deliveries.enqueue(r.channelId(), event.id());
                return Optional.of(event);
            }
        return Optional.empty();
    }
    @Transactional
    public AlertEvent acknowledge(long id, String op, long version) {
        if (!alerts.acknowledge(id, op, version))
            throw new BusinessException("VERSION_CONFLICT", "alert event changed");
        return event(id);
    }
    @Transactional
    public AlertEvent resolve(long id, long version) {
        if (!alerts.resolve(id, version))
            throw new BusinessException("VERSION_CONFLICT", "alert event changed");
        return event(id);
    }
    @Transactional
    public AlertEvent silence(long id, Instant until, long version) {
        if (until == null || !until.isAfter(Instant.now()))
            throw new BusinessException("INVALID_ARGUMENT", "silenceUntil must be in the future");
        if (!alerts.silence(id, until, version))
            throw new BusinessException("VERSION_CONFLICT", "alert event changed");
        return event(id);
    }
    private static boolean matches(Double threshold, Double value) {
        return threshold == null || value != null && value >= threshold;
    }
    private static String json(Double v, String e) {
        return "{\"value\":" + (v == null ? "null" : v) + ",\"evidence\":\"" + escape(e) + "\"}";
    }
    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
