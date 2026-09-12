ALTER TABLE redis_credential
    DROP INDEX uk_credential_name,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER auth_type,
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER updated_at,
    ADD COLUMN active_name VARCHAR(128)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) STORED AFTER deleted_at,
    ADD UNIQUE KEY uk_credential_name_active (active_name);

ALTER TABLE application
    DROP INDEX uk_application_code,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER updated_at,
    ADD COLUMN active_code VARCHAR(128)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, app_code, NULL)) STORED AFTER deleted_at,
    ADD UNIQUE KEY uk_application_code_active (active_code);

ALTER TABLE audit_log
    ADD COLUMN request_id VARCHAR(64) NULL AFTER result,
    ADD COLUMN request_digest VARCHAR(128) NULL AFTER request_id;

CREATE TABLE async_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    biz_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    lease_owner VARCHAR(160) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_async_job_idempotency (idempotency_key),
    KEY idx_async_job_poll (status, next_run_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(128) NOT NULL,
    request_digest VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_idempotency_operator_key (operator_id, idempotency_key),
    KEY idx_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
