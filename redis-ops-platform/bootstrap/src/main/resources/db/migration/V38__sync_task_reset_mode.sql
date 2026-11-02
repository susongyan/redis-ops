ALTER TABLE sync_task
    ADD COLUMN reset_mode VARCHAR(32) NOT NULL DEFAULT 'PRESERVE' AFTER command_policy_json;
