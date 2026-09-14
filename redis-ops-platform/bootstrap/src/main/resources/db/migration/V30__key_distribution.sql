CREATE TABLE key_distribution_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 cluster_id BIGINT NOT NULL,
 spec_json JSON NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 reason VARCHAR(64) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 observed BIGINT NOT NULL DEFAULT 0,
 capacity_reached BOOLEAN NOT NULL DEFAULT FALSE,
 completed_shards INT NOT NULL DEFAULT 0,
 total_shards INT NOT NULL DEFAULT 0,
 elapsed_millis BIGINT NOT NULL DEFAULT 0,
 checkpoint_json MEDIUMTEXT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_distribution_queue(status,id),
 KEY idx_distribution_cluster(cluster_id,status),
 CONSTRAINT fk_distribution_cluster FOREIGN KEY(cluster_id) REFERENCES redis_cluster(id)
);
CREATE TABLE key_distribution_runtime (
 cluster_id BIGINT NOT NULL PRIMARY KEY,
 task_id BIGINT NULL,
 owner VARCHAR(64) NULL,
 generation BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(3) NULL,
 preview_after DATETIME(3) NULL,
 KEY idx_distribution_lease(lease_until),
 CONSTRAINT fk_distribution_runtime_cluster FOREIGN KEY(cluster_id) REFERENCES redis_cluster(id)
);
CREATE TABLE key_distribution_group (
 task_id BIGINT NOT NULL,
 ordinal_no INT NOT NULL,
 rule_id VARCHAR(32) NOT NULL,
 group_text VARCHAR(256) NOT NULL,
 system_bucket BOOLEAN NOT NULL,
 observed_count BIGINT NOT NULL,
 error_count BIGINT NOT NULL,
 PRIMARY KEY(task_id,ordinal_no),
 CONSTRAINT fk_distribution_group_task FOREIGN KEY(task_id) REFERENCES key_distribution_task(id) ON DELETE CASCADE
);
