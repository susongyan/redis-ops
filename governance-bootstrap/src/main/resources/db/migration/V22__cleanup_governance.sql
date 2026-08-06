CREATE TABLE cleanup_governance_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(48) NOT NULL,
  cluster_id BIGINT NOT NULL,
  database_no INT NOT NULL DEFAULT 0,
  include_pattern VARCHAR(512) NOT NULL DEFAULT '*',
  impact_limit BIGINT NOT NULL,
  scan_rate_per_second INT NOT NULL DEFAULT 200,
  status VARCHAR(32) NOT NULL,
  approval_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  approval_note VARCHAR(512) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cleanup_governance_task_no (task_no), KEY idx_cleanup_governance_cluster (cluster_id, status)
);
CREATE TABLE cleanup_governance_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  run_no VARCHAR(48) NOT NULL,
  status VARCHAR(24) NOT NULL,
  planned_keys BIGINT NOT NULL DEFAULT 0,
  scanned_keys BIGINT NOT NULL DEFAULT 0,
  candidate_keys BIGINT NOT NULL DEFAULT 0,
  deleted_keys BIGINT NOT NULL DEFAULT 0,
  skipped_keys BIGINT NOT NULL DEFAULT 0,
  failed_keys BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMP(3) NULL,
  error_code VARCHAR(80) NULL,
  UNIQUE KEY uk_cleanup_governance_run_no (run_no), KEY idx_cleanup_governance_run_task (task_id, started_at DESC)
);
CREATE TABLE cleanup_governance_checkpoint (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  shard_id VARCHAR(128) NOT NULL,
  cursor_value VARCHAR(128) NOT NULL DEFAULT '0',
  scanned_keys BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cleanup_governance_checkpoint (run_id, shard_id)
);
