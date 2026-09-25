-- Existing definitions remain explicit COMMAND nodes; no command is enabled by this migration.
ALTER TABLE operation_command_definition
  MODIFY command_name VARCHAR(96) NOT NULL,
  ADD COLUMN node_kind VARCHAR(16) NOT NULL DEFAULT 'COMMAND',
  ADD COLUMN parent_id BIGINT NULL,
  ADD KEY idx_operation_command_parent (parent_id);

-- Serialize catalog writes across Platform instances, including create/name checks.
CREATE TABLE operation_catalog_lock (
  id INT NOT NULL PRIMARY KEY
);
INSERT INTO operation_catalog_lock (id) VALUES (1);

-- Group existing standard categories without enabling any executable command.
INSERT INTO operation_command_definition
  (command_name, category, access_mode, risk_level, enabled, parameter_schema_json,
   key_position, routing_policy, approval_policy, allowed_data_types_json, node_kind)
SELECT DISTINCT CONCAT('GROUP.', category), category, 'READ', 'LOW', TRUE, JSON_ARRAY(),
       0, 'CONTAINER', 'CONFIRM', JSON_ARRAY('key'), 'CATEGORY'
FROM operation_command_definition
WHERE category IN ('KEY','STRING','HASH','LIST','SET','ZSET','STREAM');

UPDATE operation_command_definition child
JOIN operation_command_definition parent
  ON parent.command_name=CONCAT('GROUP.',child.category) AND parent.node_kind='CATEGORY'
SET child.parent_id=parent.id, child.version=child.version+1
WHERE child.node_kind='COMMAND' AND child.parent_id IS NULL;

-- Removed workflow: keep or strengthen confirmation; never downgrade approval to direct execution.
UPDATE operation_command_definition
SET approval_policy='DANGER_CONFIRM', version=version+1
WHERE approval_policy='APPROVAL';
