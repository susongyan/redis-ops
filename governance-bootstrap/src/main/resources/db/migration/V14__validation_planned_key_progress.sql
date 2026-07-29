ALTER TABLE validation_run
    ADD COLUMN planned_keys BIGINT NOT NULL DEFAULT 0 AFTER status;
