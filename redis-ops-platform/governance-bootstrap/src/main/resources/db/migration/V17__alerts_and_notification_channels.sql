CREATE TABLE notification_channel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  channel_uuid CHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  channel_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  encrypted_config BLOB NOT NULL,
  key_id VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_notification_channel_uuid (channel_uuid),
  UNIQUE KEY uk_notification_channel_name (name)
);
CREATE TABLE alert_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  rule_type VARCHAR(48) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  threshold_value DOUBLE NULL,
  duration_seconds INT NOT NULL DEFAULT 0,
  channel_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_alert_rule_name (name), KEY idx_alert_rule_enabled (enabled, rule_type)
);
CREATE TABLE alert_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL,
  resource_type VARCHAR(48) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  title VARCHAR(256) NOT NULL,
  evidence_json JSON NULL,
  first_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acknowledged_at TIMESTAMP(3) NULL,
  acknowledged_by VARCHAR(128) NULL,
  resolved_at TIMESTAMP(3) NULL,
  silence_until TIMESTAMP(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_alert_event_dedup (rule_id, resource_type, resource_id),
  KEY idx_alert_event_status (status, severity, last_seen_at DESC)
);
CREATE TABLE notification_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  channel_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(3) NULL,
  last_error VARCHAR(1000) NULL,
  delivered_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_notification_record_delivery (status, next_attempt_at)
);
