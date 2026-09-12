ALTER TABLE operation_command_definition
    ADD COLUMN allowed_data_types_json JSON NULL,
    ADD COLUMN missing_key_policy VARCHAR(24) NOT NULL DEFAULT 'EXISTING_REQUIRED',
    ADD COLUMN blocked_by_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN change_reason VARCHAR(512) NULL,
    ADD COLUMN updated_by VARCHAR(128) NULL;

UPDATE operation_command_definition SET allowed_data_types_json='["string"]', missing_key_policy='EXISTING_REQUIRED' WHERE command_name='GET';
UPDATE operation_command_definition SET allowed_data_types_json='["string","hash","list","set","zset","stream","none"]', missing_key_policy='EXISTING_REQUIRED' WHERE command_name IN ('TTL','TYPE','EXISTS');
UPDATE operation_command_definition SET allowed_data_types_json='["string"]', missing_key_policy='CREATE_ALLOWED' WHERE command_name='SET';
UPDATE operation_command_definition SET allowed_data_types_json='["string","hash","list","set","zset","stream"]', missing_key_policy='EXISTING_REQUIRED' WHERE command_name IN ('EXPIRE','PERSIST');
UPDATE operation_command_definition SET allowed_data_types_json='["hash"]', missing_key_policy='EXISTING_REQUIRED' WHERE command_name IN ('HGET','HDEL');
UPDATE operation_command_definition SET allowed_data_types_json='["hash"]', missing_key_policy='CREATE_ALLOWED' WHERE command_name='HSET';
UPDATE operation_command_definition SET allowed_data_types_json='["key"]', missing_key_policy='EXISTING_REQUIRED' WHERE command_name='UNLINK';
ALTER TABLE operation_command_definition MODIFY COLUMN allowed_data_types_json JSON NOT NULL;
