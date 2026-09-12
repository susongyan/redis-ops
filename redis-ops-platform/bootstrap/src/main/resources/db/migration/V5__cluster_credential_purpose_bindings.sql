CREATE TABLE cluster_credential_binding (
    cluster_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    credential_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (cluster_id, purpose),
    KEY idx_credential_binding_credential (credential_id, cluster_id),
    CONSTRAINT fk_credential_binding_cluster FOREIGN KEY (cluster_id) REFERENCES redis_cluster(id),
    CONSTRAINT fk_credential_binding_credential FOREIGN KEY (credential_id) REFERENCES redis_credential(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO cluster_credential_binding(cluster_id,purpose,credential_id)
SELECT id,'DISCOVERY',credential_id
FROM redis_cluster
WHERE credential_id IS NOT NULL;

ALTER TABLE redis_cluster
    DROP FOREIGN KEY fk_cluster_credential,
    DROP COLUMN credential_id;
