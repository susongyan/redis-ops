INSERT INTO operation_command_definition
    (command_name, category, access_mode, risk_level, enabled, parameter_schema_json,
     key_position, routing_policy, approval_policy, max_value_bytes, allowed_data_types_json)
SELECT 'SADD', 'SET', 'WRITE', 'MEDIUM', TRUE,
       '[{"name":"key","type":"REDIS_KEY","required":true},{"name":"member","type":"VALUE","required":true}]',
       1, 'SINGLE_KEY', 'CONFIRM', 4096, '["set"]'
WHERE NOT EXISTS (SELECT 1 FROM operation_command_definition WHERE command_name='SADD' AND command_version=1);

INSERT INTO operation_command_definition
    (command_name, category, access_mode, risk_level, enabled, parameter_schema_json,
     key_position, routing_policy, approval_policy, max_value_bytes, allowed_data_types_json)
SELECT 'SREM', 'SET', 'WRITE', 'HIGH', TRUE,
       '[{"name":"key","type":"REDIS_KEY","required":true},{"name":"member","type":"VALUE","required":true}]',
       1, 'SINGLE_KEY', 'APPROVAL', 4096, '["set"]'
WHERE NOT EXISTS (SELECT 1 FROM operation_command_definition WHERE command_name='SREM' AND command_version=1);

INSERT INTO operation_command_definition
    (command_name, category, access_mode, risk_level, enabled, parameter_schema_json,
     key_position, routing_policy, approval_policy, max_value_bytes, allowed_data_types_json)
SELECT 'ZADD', 'ZSET', 'WRITE', 'MEDIUM', TRUE,
       '[{"name":"key","type":"REDIS_KEY","required":true},{"name":"score","type":"NUMBER","required":true},{"name":"member","type":"VALUE","required":true}]',
       1, 'SINGLE_KEY', 'CONFIRM', 4096, '["zset"]'
WHERE NOT EXISTS (SELECT 1 FROM operation_command_definition WHERE command_name='ZADD' AND command_version=1);

INSERT INTO operation_command_definition
    (command_name, category, access_mode, risk_level, enabled, parameter_schema_json,
     key_position, routing_policy, approval_policy, max_value_bytes, allowed_data_types_json)
SELECT 'ZREM', 'ZSET', 'WRITE', 'HIGH', TRUE,
       '[{"name":"key","type":"REDIS_KEY","required":true},{"name":"member","type":"VALUE","required":true}]',
       1, 'SINGLE_KEY', 'APPROVAL', 4096, '["zset"]'
WHERE NOT EXISTS (SELECT 1 FROM operation_command_definition WHERE command_name='ZREM' AND command_version=1);

UPDATE operation_command_definition SET allowed_data_types_json='["set"]', missing_key_policy='CREATE_ALLOWED'
WHERE command_name='SADD';
UPDATE operation_command_definition SET allowed_data_types_json='["set"]', missing_key_policy='EXISTING_REQUIRED'
WHERE command_name='SREM';
UPDATE operation_command_definition SET allowed_data_types_json='["zset"]', missing_key_policy='CREATE_ALLOWED'
WHERE command_name='ZADD';
UPDATE operation_command_definition SET allowed_data_types_json='["zset"]', missing_key_policy='EXISTING_REQUIRED'
WHERE command_name='ZREM';
