ALTER TABLE redis_credential
    MODIFY COLUMN secret_ref VARCHAR(512) NULL,
    ADD COLUMN credential_uuid CHAR(36) NULL AFTER id,
    ADD COLUMN encrypted_secret BLOB NULL AFTER name,
    ADD COLUMN key_id VARCHAR(64) NULL AFTER encrypted_secret,
    ADD COLUMN secret_status VARCHAR(32) NOT NULL DEFAULT 'LEGACY_ENV' AFTER key_id;

UPDATE redis_credential SET credential_uuid=UUID() WHERE credential_uuid IS NULL;
UPDATE redis_credential
SET secret_status=CASE
    WHEN secret_ref LIKE 'env://%' THEN 'LEGACY_ENV'
    ELSE 'UNCONFIGURED'
END
WHERE encrypted_secret IS NULL;

ALTER TABLE redis_credential
    MODIFY COLUMN credential_uuid CHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_credential_uuid (credential_uuid),
    ADD KEY idx_credential_rotation (secret_status,key_id);
