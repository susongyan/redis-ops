ALTER TABLE operation_command_definition ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_at;
