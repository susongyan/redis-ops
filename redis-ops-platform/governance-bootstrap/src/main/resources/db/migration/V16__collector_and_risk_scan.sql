CREATE TABLE collector_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_id BIGINT NOT NULL,
  collection_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMP(3) NULL,
  summary_json JSON NULL,
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(1000) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_collector_run_cluster (cluster_id, collection_type, created_at DESC)
);

CREATE TABLE scan_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(48) NOT NULL,
  cluster_id BIGINT NOT NULL,
  database_no INT NOT NULL DEFAULT 0,
  include_pattern VARCHAR(512) NOT NULL DEFAULT '*',
  large_key_threshold_bytes BIGINT NOT NULL,
  scan_rate_per_second INT NOT NULL,
  max_findings INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_scan_task_no (task_no),
  KEY idx_scan_task_cluster (cluster_id, status)
);

CREATE TABLE scan_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  run_no VARCHAR(48) NOT NULL,
  status VARCHAR(24) NOT NULL,
  scanned_keys BIGINT NOT NULL DEFAULT 0,
  finding_count BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMP(3) NULL,
  error_code VARCHAR(80) NULL,
  UNIQUE KEY uk_scan_run_no (run_no),
  KEY idx_scan_run_task (task_id, started_at DESC)
);

CREATE TABLE scan_shard_checkpoint (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  shard_id VARCHAR(128) NOT NULL,
  cursor_value VARCHAR(128) NOT NULL DEFAULT '0',
  scanned_keys BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scan_shard (run_id, shard_id)
);

CREATE TABLE risk_finding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  risk_type VARCHAR(32) NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  key_hash CHAR(64) NOT NULL,
  redis_type VARCHAR(32) NULL,
  memory_bytes BIGINT NULL,
  element_count BIGINT NULL,
  ttl_seconds BIGINT NULL,
  node_id VARCHAR(128) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_risk_finding_run (run_id, risk_type, risk_level)
);
