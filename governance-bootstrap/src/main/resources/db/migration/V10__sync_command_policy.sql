ALTER TABLE sync_task
    ADD COLUMN command_policy_json JSON NULL AFTER exclude_patterns_json;

UPDATE sync_task
SET command_policy_json = '{"allowDestructiveCommands":false,"allowSafeSplit":true,"additionalBlockedCommands":[],"policyVersion":"v1"}'
WHERE command_policy_json IS NULL;

ALTER TABLE sync_task
    MODIFY COLUMN command_policy_json JSON NOT NULL;
