package io.github.redisops.infrastructure.persistence;
import io.github.redisops.domain.alert.*;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AlertMapper {
    @Insert("INSERT INTO alert_rule(name,rule_type,severity,enabled,threshold_value,duration_seconds,channel_id,version) VALUES(#{name},#{ruleType},#{severity},#{enabled},#{thresholdValue},#{durationSeconds},#{channelId},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertRule(RuleRow r);
    @Select("SELECT id,name,rule_type ruleType,severity,enabled,threshold_value thresholdValue,duration_seconds durationSeconds,channel_id channelId,version,created_at createdAt,updated_at updatedAt FROM alert_rule WHERE id=#{id}")
    AlertRule rule(long id);
    @Select("SELECT id,name,rule_type ruleType,severity,enabled,threshold_value thresholdValue,duration_seconds durationSeconds,channel_id channelId,version,created_at createdAt,updated_at updatedAt FROM alert_rule ORDER BY id DESC")
    List<AlertRule> rules();
    @Update("UPDATE alert_rule SET enabled=#{enabled},threshold_value=#{thresholdValue},duration_seconds=#{durationSeconds},channel_id=#{channelId},version=version+1 WHERE id=#{id} AND version=#{version}")
    int updateRule(AlertRule r);
    @Insert("INSERT INTO alert_event(rule_id,resource_type,resource_id,status,severity,title,evidence_json) VALUES(#{ruleId},#{resourceType},#{resourceId},'OPEN',#{severity},#{title},CAST(#{evidenceJson} AS JSON)) ON DUPLICATE KEY UPDATE status='OPEN',severity=VALUES(severity),title=VALUES(title),evidence_json=VALUES(evidence_json),last_seen_at=CURRENT_TIMESTAMP(3),resolved_at=NULL,version=version+1")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void upsert(EventRow r);
    @Select("SELECT id,rule_id ruleId,resource_type resourceType,resource_id resourceId,status,severity,title,CAST(evidence_json AS CHAR) evidenceJson,first_seen_at firstSeenAt,last_seen_at lastSeenAt,acknowledged_at acknowledgedAt,acknowledged_by acknowledgedBy,resolved_at resolvedAt,silence_until silenceUntil,version FROM alert_event WHERE rule_id=#{ruleId} AND resource_type=#{resourceType} AND resource_id=#{resourceId}")
    AlertEvent eventByKey(EventRow r);
    @Select("SELECT id,rule_id ruleId,resource_type resourceType,resource_id resourceId,status,severity,title,CAST(evidence_json AS CHAR) evidenceJson,first_seen_at firstSeenAt,last_seen_at lastSeenAt,acknowledged_at acknowledgedAt,acknowledged_by acknowledgedBy,resolved_at resolvedAt,silence_until silenceUntil,version FROM alert_event WHERE id=#{id}")
    AlertEvent event(long id);
    @Select("SELECT id,rule_id ruleId,resource_type resourceType,resource_id resourceId,status,severity,title,CAST(evidence_json AS CHAR) evidenceJson,first_seen_at firstSeenAt,last_seen_at lastSeenAt,acknowledged_at acknowledgedAt,acknowledged_by acknowledgedBy,resolved_at resolvedAt,silence_until silenceUntil,version FROM alert_event WHERE (#{status} IS NULL OR status=#{status}) ORDER BY last_seen_at DESC LIMIT #{size} OFFSET #{offset}")
    List<AlertEvent> events(@Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    @Select("SELECT COUNT(*) FROM alert_event WHERE (#{status} IS NULL OR status=#{status})")
    long count(@Param("status") String status);
    @Update("UPDATE alert_event SET status='ACKNOWLEDGED',acknowledged_at=CURRENT_TIMESTAMP(3),acknowledged_by=#{operator},version=version+1 WHERE id=#{id} AND version=#{version} AND status='OPEN'")
    int acknowledge(@Param("id") long id, @Param("operator") String operator, @Param("version") long version);
    @Update("UPDATE alert_event SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND version=#{version} AND status!='RESOLVED'")
    int resolve(@Param("id") long id, @Param("version") long version);
    @Update("UPDATE alert_event SET silence_until=#{until},version=version+1 WHERE id=#{id} AND version=#{version}")
    int silence(@Param("id") long id, @Param("until") java.time.Instant until, @Param("version") long version);
    class RuleRow {
        public Long id;
        public String name, ruleType, severity;
        public boolean enabled;
        public Double thresholdValue;
        public int durationSeconds;
        public Long channelId;
    }
    class EventRow {
        public Long id;
        public long ruleId;
        public String resourceType, resourceId, severity, title, evidenceJson;
    }
}
