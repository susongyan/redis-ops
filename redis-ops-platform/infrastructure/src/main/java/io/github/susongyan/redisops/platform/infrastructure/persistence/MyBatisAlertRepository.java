package io.github.susongyan.redisops.platform.infrastructure.persistence;
import io.github.susongyan.redisops.platform.common.PageResult;
import io.github.susongyan.redisops.platform.domain.alert.*;
import java.util.*;
import org.springframework.stereotype.Repository;
@Repository
public class MyBatisAlertRepository implements AlertRepository {
    private final AlertMapper m;
    private final ActorSnapshots actors;
    public MyBatisAlertRepository(AlertMapper m, ActorSnapshots actors) {
        this.m = m;
        this.actors = actors;
    }
    public AlertRule saveRule(AlertRule r) {
        AlertMapper.RuleRow x = new AlertMapper.RuleRow();
        x.name = r.name();
        x.ruleType = r.ruleType();
        x.severity = r.severity().name();
        x.enabled = r.enabled();
        x.thresholdValue = r.thresholdValue();
        x.durationSeconds = r.durationSeconds();
        x.channelId = r.channelId();
        m.insertRule(x);
        return m.rule(x.id);
    }
    public Optional<AlertRule> findRule(long id) {
        return Optional.ofNullable(m.rule(id));
    }
    public List<AlertRule> rules() {
        return m.rules();
    }
    public boolean updateRule(AlertRule r, long v) {
        return m.updateRule(r) == 1;
    }
    public AlertEvent upsertOpen(long rid, String rt, String id, AlertSeverity s, String title, String json) {
        AlertMapper.EventRow x = new AlertMapper.EventRow();
        x.ruleId = rid;
        x.resourceType = rt;
        x.resourceId = id;
        x.severity = s.name();
        x.title = title;
        x.evidenceJson = json;
        m.upsert(x);
        return m.eventByKey(x);
    }
    public Optional<AlertEvent> findEvent(long id) {
        return Optional.ofNullable(m.event(id));
    }
    public PageResult<AlertEvent> events(AlertStatus s, int p, int z) {
        String x = s == null ? null : s.name();
        return new PageResult<>(m.events(x, (p - 1) * z, z), m.count(x), p, z);
    }
    public boolean acknowledge(long id, String o, long v) {
        return m.acknowledge(id, o, v, actors.capture(o)) == 1;
    }
    public boolean resolve(long id, long v) {
        return m.resolve(id, v) == 1;
    }
    public boolean silence(long id, java.time.Instant until, long version) {
        return m.silence(id, until, version) == 1;
    }
}
