CREATE TABLE redis_credential (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    secret_ref VARCHAR(512) NOT NULL,
    username VARCHAR(128) NULL,
    auth_type VARCHAR(32) NOT NULL DEFAULT 'PASSWORD',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_credential_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE redis_cluster (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    business_line VARCHAR(128) NULL,
    owner VARCHAR(128) NOT NULL,
    ops_owner VARCHAR(128) NULL,
    service_level VARCHAR(32) NULL,
    mode VARCHAR(32) NOT NULL,
    redis_version VARCHAR(32) NULL,
    endpoint VARCHAR(512) NOT NULL,
    credential_id BIGINT NULL,
    idc VARCHAR(64) NULL,
    region VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    active_name VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) STORED,
    UNIQUE KEY uk_cluster_name_active (active_name),
    KEY idx_cluster_filter (environment, business_line, owner, status),
    CONSTRAINT fk_cluster_credential FOREIGN KEY (credential_id) REFERENCES redis_credential(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE redis_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cluster_id BIGINT NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    node_id VARCHAR(128) NULL,
    role VARCHAR(32) NOT NULL,
    master_node_id VARCHAR(128) NULL,
    slot_ranges_json JSON NULL,
    memory_bytes BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_node_endpoint (cluster_id, host, port),
    CONSTRAINT fk_node_cluster FOREIGN KEY (cluster_id) REFERENCES redis_cluster(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    owner VARCHAR(128) NULL,
    business_line VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_application_code (app_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_cluster_binding (
    application_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    client_type VARCHAR(64) NULL,
    client_version VARCHAR(64) NULL,
    pool_config_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (application_id, cluster_id),
    KEY idx_binding_cluster (cluster_id),
    CONSTRAINT fk_binding_app FOREIGN KEY (application_id) REFERENCES application(id),
    CONSTRAINT fk_binding_cluster FOREIGN KEY (cluster_id) REFERENCES redis_cluster(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE discovery_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cluster_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    node_count INT NULL,
    error_message VARCHAR(1024) NULL,
    KEY idx_discovery_cluster_time (cluster_id, started_at),
    CONSTRAINT fk_discovery_cluster FOREIGN KEY (cluster_id) REFERENCES redis_cluster(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    result VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_audit_resource (resource_type, resource_id, created_at),
    KEY idx_audit_operator (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
