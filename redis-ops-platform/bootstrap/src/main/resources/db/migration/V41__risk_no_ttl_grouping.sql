-- NO_TTL findings switch from per-key rows to pattern-grouped aggregation; scans now always cover
-- the full keyspace, so exact totals move onto the run row (NULL = legacy run with per-key rows).
ALTER TABLE scan_task
  ADD COLUMN group_delimiter VARCHAR(8) NOT NULL DEFAULT ':' AFTER max_findings,
  ADD COLUMN group_segments INT NOT NULL DEFAULT 2 AFTER group_delimiter;

ALTER TABLE scan_run
  ADD COLUMN no_ttl_total BIGINT NULL AFTER finding_count,
  ADD COLUMN large_key_total BIGINT NULL AFTER no_ttl_total;

CREATE TABLE risk_finding_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  risk_type VARCHAR(32) NOT NULL DEFAULT 'NO_TTL',
  group_text VARCHAR(256) NOT NULL,
  hit_count BIGINT NOT NULL,
  sample_key VARCHAR(512) NULL,
  max_memory_bytes BIGINT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_group_run (run_id, risk_type, group_text)
);
