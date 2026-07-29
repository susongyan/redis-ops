ALTER TABLE validation_task
    ADD COLUMN sampling_mode VARCHAR(16) NOT NULL DEFAULT 'COUNT' AFTER sample_seed,
    ADD COLUMN sample_percentage DECIMAL(5,2) NULL AFTER sample_limit;

CREATE INDEX idx_validation_task_sync ON validation_task(sync_task_id, id DESC);
