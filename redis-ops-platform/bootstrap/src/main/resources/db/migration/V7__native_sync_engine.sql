ALTER TABLE sync_task
    ADD COLUMN source_db INT NOT NULL DEFAULT 0 AFTER tool_type,
    ADD COLUMN target_db INT NOT NULL DEFAULT 0 AFTER source_db,
    ADD COLUMN include_patterns_json JSON NULL AFTER target_db,
    ADD COLUMN exclude_patterns_json JSON NULL AFTER include_patterns_json,
    ADD COLUMN rate_limit_ops BIGINT NOT NULL DEFAULT 50000 AFTER exclude_patterns_json,
    ADD COLUMN bandwidth_limit_bytes_per_second BIGINT NOT NULL DEFAULT 104857600 AFTER rate_limit_ops,
    ADD COLUMN spool_limit_bytes BIGINT NOT NULL DEFAULT 53687091200 AFTER bandwidth_limit_bytes_per_second,
    ADD COLUMN desired_action VARCHAR(32) NULL AFTER spool_limit_bytes,
    ADD COLUMN write_fenced BOOLEAN NOT NULL DEFAULT FALSE AFTER desired_action,
    ADD COLUMN write_fence_note VARCHAR(512) NULL AFTER write_fenced,
    ADD COLUMN blocked_reason VARCHAR(128) NULL AFTER write_fence_note,
    ADD COLUMN full_sync_epoch VARCHAR(36) NULL AFTER blocked_reason;

UPDATE sync_task
SET include_patterns_json=JSON_ARRAY('*'),
    exclude_patterns_json=JSON_ARRAY(),
    tool_type='NATIVE_JAVA'
WHERE include_patterns_json IS NULL OR exclude_patterns_json IS NULL OR tool_type IS NULL;

ALTER TABLE sync_task
    MODIFY COLUMN include_patterns_json JSON NOT NULL,
    MODIFY COLUMN exclude_patterns_json JSON NOT NULL;

CREATE TABLE sync_runtime (
    task_id BIGINT NOT NULL PRIMARY KEY,
    runtime_id VARCHAR(36) NOT NULL,
    lease_owner VARCHAR(160) NULL,
    lease_until DATETIME(3) NULL,
    fencing_generation BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(32) NOT NULL,
    heartbeat_at DATETIME(3) NULL,
    spool_bytes BIGINT NOT NULL DEFAULT 0,
    recovery_action VARCHAR(64) NULL,
    last_error VARCHAR(1024) NULL,
    started_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sync_runtime_id (runtime_id),
    KEY idx_sync_runtime_lease (lease_until),
    CONSTRAINT fk_sync_runtime_task FOREIGN KEY (task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_channel_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    channel_id VARCHAR(160) NOT NULL,
    source_node_id VARCHAR(160) NULL,
    slot_ranges VARCHAR(1024) NULL,
    replication_id VARCHAR(80) NULL,
    received_offset BIGINT NOT NULL DEFAULT -1,
    applied_offset BIGINT NOT NULL DEFAULT -1,
    status VARCHAR(32) NOT NULL,
    last_heartbeat_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sync_channel (task_id,channel_id),
    KEY idx_sync_channel_status (task_id,status),
    CONSTRAINT fk_sync_channel_task FOREIGN KEY (task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_precheck_report (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    report_json JSON NOT NULL,
    checked_at DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NOT NULL,
    KEY idx_sync_precheck_latest (task_id,checked_at),
    CONSTRAINT fk_sync_precheck_task FOREIGN KEY (task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_metric_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    channel_id VARCHAR(160) NULL,
    timestamp_lag_seconds BIGINT NULL,
    estimated_lag_seconds BIGINT NULL,
    offset_gap_bytes BIGINT NOT NULL DEFAULT 0,
    backlog_bytes BIGINT NOT NULL DEFAULT 0,
    source_bytes_per_second BIGINT NOT NULL DEFAULT 0,
    target_apply_bytes_per_second BIGINT NOT NULL DEFAULT 0,
    catch_up_eta_seconds BIGINT NULL,
    calculation_method VARCHAR(32) NOT NULL,
    confidence VARCHAR(16) NOT NULL,
    collected_at DATETIME(3) NOT NULL,
    KEY idx_sync_metric_task_time (task_id,collected_at),
    CONSTRAINT fk_sync_metric_task FOREIGN KEY (task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE switchover
    ADD COLUMN source_write_fenced BOOLEAN NOT NULL DEFAULT FALSE AFTER operator_id,
    ADD COLUMN source_fence_note VARCHAR(512) NULL AFTER source_write_fenced;
