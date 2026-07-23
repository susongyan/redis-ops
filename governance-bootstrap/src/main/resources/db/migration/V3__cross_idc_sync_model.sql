CREATE TABLE region (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(512) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    active_code VARCHAR(64) GENERATED ALWAYS AS (IF(deleted_at IS NULL, code, NULL)) STORED,
    UNIQUE KEY uk_region_code_active (active_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idc (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    region_id BIGINT NOT NULL,
    network_domain VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(512) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    active_region_code VARCHAR(160) GENERATED ALWAYS AS (IF(deleted_at IS NULL, CONCAT(region_id, ':', code), NULL)) STORED,
    UNIQUE KEY uk_idc_region_code_active (active_region_code),
    KEY idx_idc_region (region_id),
    CONSTRAINT fk_idc_region FOREIGN KEY (region_id) REFERENCES region(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO region(code,name)
SELECT DISTINCT COALESCE(region, '__legacy_unassigned__'), COALESCE(region, '未指定地域')
FROM redis_cluster WHERE deleted_at IS NULL AND (region IS NOT NULL OR idc IS NOT NULL);

INSERT INTO idc(code,name,region_id)
SELECT DISTINCT COALESCE(c.idc, '__region_default__'), COALESCE(c.idc, '未指定机房'), r.id
FROM redis_cluster c
JOIN region r ON r.code=COALESCE(c.region, '__legacy_unassigned__') AND r.deleted_at IS NULL
WHERE c.deleted_at IS NULL AND (c.region IS NOT NULL OR c.idc IS NOT NULL);

ALTER TABLE redis_cluster ADD COLUMN idc_id BIGINT NULL AFTER credential_id;
UPDATE redis_cluster c
JOIN region r ON r.code=COALESCE(c.region, '__legacy_unassigned__') AND r.deleted_at IS NULL
JOIN idc i ON i.region_id=r.id AND i.code=COALESCE(c.idc, '__region_default__') AND i.deleted_at IS NULL
SET c.idc_id=i.id
WHERE c.region IS NOT NULL OR c.idc IS NOT NULL;
ALTER TABLE redis_cluster
    ADD KEY idx_cluster_idc (idc_id),
    ADD CONSTRAINT fk_cluster_idc FOREIGN KEY (idc_id) REFERENCES idc(id),
    DROP COLUMN idc,
    DROP COLUMN region;

CREATE TABLE cluster_relation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    primary_cluster_id BIGINT NOT NULL,
    standby_cluster_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    desired_rpo_seconds BIGINT NOT NULL,
    description VARCHAR(512) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    active_pair VARCHAR(80) GENERATED ALWAYS AS (
      IF(deleted_at IS NULL, CONCAT(LEAST(primary_cluster_id,standby_cluster_id), ':', GREATEST(primary_cluster_id,standby_cluster_id)), NULL)
    ) STORED,
    UNIQUE KEY uk_cluster_relation_pair_active (active_pair),
    KEY idx_relation_primary (primary_cluster_id),
    KEY idx_relation_standby (standby_cluster_id),
    CONSTRAINT fk_relation_primary FOREIGN KEY (primary_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_relation_standby FOREIGN KEY (standby_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT chk_relation_clusters CHECK (primary_cluster_id <> standby_cluster_id),
    CONSTRAINT chk_relation_rpo CHECK (desired_rpo_seconds > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_no VARCHAR(64) NOT NULL,
    relation_id BIGINT NULL,
    source_cluster_id BIGINT NOT NULL,
    target_cluster_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    sync_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    tool_type VARCHAR(64) NULL,
    last_rpo_seconds BIGINT NULL,
    last_error VARCHAR(1024) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    UNIQUE KEY uk_sync_task_no (task_no),
    KEY idx_sync_task_relation (relation_id, created_at),
    KEY idx_sync_task_status (status),
    CONSTRAINT fk_sync_task_relation FOREIGN KEY (relation_id) REFERENCES cluster_relation(id),
    CONSTRAINT fk_sync_task_source FOREIGN KEY (source_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_sync_task_target FOREIGN KEY (target_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT chk_sync_task_clusters CHECK (source_cluster_id <> target_cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    message VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_sync_event_task (task_id, created_at),
    CONSTRAINT fk_sync_event_task FOREIGN KEY (task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE switchover (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    relation_id BIGINT NOT NULL,
    old_primary_cluster_id BIGINT NOT NULL,
    old_standby_cluster_id BIGINT NOT NULL,
    stopped_task_id BIGINT NOT NULL,
    reverse_task_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    last_error VARCHAR(1024) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    confirmed_at DATETIME(3) NULL,
    KEY idx_switchover_relation (relation_id, created_at),
    CONSTRAINT fk_switchover_relation FOREIGN KEY (relation_id) REFERENCES cluster_relation(id),
    CONSTRAINT fk_switchover_old_primary FOREIGN KEY (old_primary_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_switchover_old_standby FOREIGN KEY (old_standby_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_switchover_stopped_task FOREIGN KEY (stopped_task_id) REFERENCES sync_task(id),
    CONSTRAINT fk_switchover_reverse_task FOREIGN KEY (reverse_task_id) REFERENCES sync_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
