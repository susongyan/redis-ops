ALTER TABLE sync_task
    ADD COLUMN full_apply_concurrency INT NOT NULL DEFAULT 4 AFTER spool_limit_bytes,
    ADD COLUMN full_apply_pipeline_size INT NOT NULL DEFAULT 100 AFTER full_apply_concurrency;
