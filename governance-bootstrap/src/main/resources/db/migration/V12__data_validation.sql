CREATE TABLE validation_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL,
    sync_task_id BIGINT NULL,
    source_cluster_id BIGINT NOT NULL,
    target_cluster_id BIGINT NOT NULL,
    source_db INT NOT NULL,
    target_db INT NOT NULL,
    strictness VARCHAR(16) NOT NULL,
    include_patterns_json JSON NOT NULL,
    exclude_patterns_json JSON NOT NULL,
    sample_seed VARCHAR(64) NOT NULL,
    sample_limit INT NOT NULL,
    ttl_tolerance_seconds BIGINT NOT NULL,
    large_key_threshold_bytes BIGINT NOT NULL,
    max_deep_compare_bytes BIGINT NOT NULL,
    chunk_bytes INT NOT NULL,
    max_elements_per_key INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_error VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_validation_task_no(task_no),
    KEY idx_validation_task_status(status),
    CONSTRAINT fk_validation_sync_task FOREIGN KEY(sync_task_id) REFERENCES sync_task(id),
    CONSTRAINT fk_validation_source_cluster FOREIGN KEY(source_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_validation_target_cluster FOREIGN KEY(target_cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT chk_validation_clusters CHECK(source_cluster_id <> target_cluster_id)
);

CREATE TABLE validation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    run_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    scanned_keys BIGINT NOT NULL DEFAULT 0,
    compared_keys BIGINT NOT NULL DEFAULT 0,
    difference_count BIGINT NOT NULL DEFAULT 0,
    degraded_count BIGINT NOT NULL DEFAULT 0,
    unverifiable_count BIGINT NOT NULL DEFAULT 0,
    inconclusive_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    summary_json JSON NULL,
    UNIQUE KEY uk_validation_run_no(run_no),
    KEY idx_validation_run_task(task_id, id DESC),
    CONSTRAINT fk_validation_run_task FOREIGN KEY(task_id) REFERENCES validation_task(id)
);

CREATE TABLE validation_shard_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    shard_id VARCHAR(128) NOT NULL,
    scan_cursor VARCHAR(128) NOT NULL,
    scanned_keys BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_validation_shard_checkpoint(run_id, direction, shard_id),
    CONSTRAINT fk_validation_checkpoint_run FOREIGN KEY(run_id) REFERENCES validation_run(id)
);

CREATE TABLE validation_difference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    difference_type VARCHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    redis_type VARCHAR(32) NULL,
    source_size BIGINT NULL,
    target_size BIGINT NULL,
    source_ttl_seconds BIGINT NULL,
    target_ttl_seconds BIGINT NULL,
    comparison_level VARCHAR(32) NOT NULL,
    degraded_reason VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_validation_difference_run(run_id, id DESC),
    KEY idx_validation_difference_type(run_id, difference_type),
    CONSTRAINT fk_validation_difference_run FOREIGN KEY(run_id) REFERENCES validation_run(id)
);
