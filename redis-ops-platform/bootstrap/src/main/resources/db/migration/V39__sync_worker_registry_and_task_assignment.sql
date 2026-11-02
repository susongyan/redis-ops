CREATE TABLE sync_worker_registry (
    instance_name VARCHAR(128) NOT NULL COMMENT 'Stable worker identity (sync.engine.instance-id, survives restarts)',
    worker_ip VARCHAR(64) NULL COMMENT 'IP last reported by this instance name',
    version VARCHAR(128) NULL COMMENT 'Worker build label',
    started_at TIMESTAMP(3) NULL COMMENT 'First registration time (kept across restarts)',
    heartbeat_at TIMESTAMP(3) NOT NULL COMMENT 'Last heartbeat; fresh = online',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (instance_name),
    KEY idx_sync_worker_registry_heartbeat (heartbeat_at)
-- No explicit charset/collation: the table must inherit the database default so
-- instance_name lands on the same collation as sync_task.assigned_worker — the
-- claim predicate compares those two columns and a mix fails with ER_COLLATION_CHARSET_MISMATCH.
) ENGINE = InnoDB COMMENT = 'Worker instance registry; liveness = heartbeat freshness';

ALTER TABLE sync_task
    ADD COLUMN assigned_worker VARCHAR(128) NULL COMMENT 'Worker instance chosen at precheck; NULL = any worker may claim' AFTER command_policy_json,
    ADD KEY idx_sync_task_assigned_worker (assigned_worker);
