-- Snapshot only new actions; do not misrepresent current names as historical names.
ALTER TABLE audit_log ADD COLUMN operator_snapshot JSON NULL;
ALTER TABLE sync_task_event ADD COLUMN operator_snapshot JSON NULL;
ALTER TABLE switchover ADD COLUMN operator_snapshot JSON NULL;
ALTER TABLE alert_event ADD COLUMN acknowledged_by_snapshot JSON NULL;
ALTER TABLE operation_command_definition ADD COLUMN updated_by_snapshot JSON NULL;
ALTER TABLE redis_operation
 ADD COLUMN operator_snapshot JSON NULL,
 ADD COLUMN approver_snapshot JSON NULL,
 ADD COLUMN executor_name VARCHAR(128) NULL,
 ADD COLUMN executor_snapshot JSON NULL;
