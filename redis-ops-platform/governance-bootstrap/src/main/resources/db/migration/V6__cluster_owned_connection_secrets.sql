CREATE TABLE redis_cluster_secret (
    cluster_id BIGINT NOT NULL,
    secret_uuid CHAR(36) NOT NULL,
    encrypted_secret BLOB NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    username VARCHAR(128) NULL,
    secret_status VARCHAR(32) NOT NULL DEFAULT 'ENCRYPTED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (cluster_id),
    UNIQUE KEY uk_cluster_secret_uuid (secret_uuid),
    KEY idx_cluster_secret_rotation (secret_status,key_id),
    CONSTRAINT fk_cluster_secret_cluster FOREIGN KEY (cluster_id) REFERENCES redis_cluster(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS cluster_credential_binding;
DROP TABLE IF EXISTS redis_credential;
