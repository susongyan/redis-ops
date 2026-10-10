-- Flatten authorization without broadening any legacy inherited permission.
ALTER TABLE operation_command_definition
  ADD COLUMN deleted_at DATETIME(3) NULL,
  ADD COLUMN active_command_name VARCHAR(96) GENERATED ALWAYS AS
    (IF(deleted_at IS NULL, command_name, NULL)) STORED,
  DROP INDEX uk_operation_command,
  ADD UNIQUE KEY uk_operation_command (active_command_name, command_version);

-- Freeze descendants of disabled or denied ancestors before removing ancestry.
CREATE TEMPORARY TABLE console_blocked_descendants (id BIGINT PRIMARY KEY);
INSERT INTO console_blocked_descendants
WITH RECURSIVE blocked AS (
  SELECT id FROM operation_command_definition WHERE enabled=FALSE OR approval_policy='DENY'
  UNION ALL
  SELECT c.id FROM operation_command_definition c JOIN blocked b ON c.parent_id=b.id
)
SELECT DISTINCT id FROM blocked;
UPDATE operation_command_definition SET enabled=FALSE, version=version+1
WHERE id IN (SELECT id FROM console_blocked_descendants);
DROP TEMPORARY TABLE console_blocked_descendants;

UPDATE operation_command_definition SET enabled=FALSE, approval_policy='DANGER_CONFIRM', version=version+1
WHERE approval_policy='INHERIT' OR node_kind='WILDCARD';

-- Preserve a command family's manual group when flattening its children.
UPDATE operation_command_definition c JOIN operation_command_definition f ON c.parent_id=f.id
SET c.parent_id=f.parent_id, c.version=c.version+1 WHERE f.node_kind='FAMILY';
UPDATE operation_command_definition SET deleted_at=CURRENT_TIMESTAMP(3),enabled=FALSE,version=version+1
WHERE node_kind IN ('FAMILY','WILDCARD');
UPDATE operation_command_definition SET parent_id=NULL,enabled=TRUE,approval_policy='DIRECT',version=version+1
WHERE node_kind='CATEGORY';
