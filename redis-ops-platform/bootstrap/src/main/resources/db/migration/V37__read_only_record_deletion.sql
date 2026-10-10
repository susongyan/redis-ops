ALTER TABLE key_distribution_task ADD COLUMN deleted_at DATETIME(3) NULL;
ALTER TABLE validation_task ADD COLUMN deleted_at DATETIME(3) NULL;
ALTER TABLE analysis_run ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
