CREATE TABLE IF NOT EXISTS analysis_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    analysis_type VARCHAR(32) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(128),
    protocol VARCHAR(32) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(2000),
    confidence DECIMAL(5,4) NOT NULL,
    evidence_json JSON NOT NULL,
    recommendations_json JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_analysis_run_request (request_id),
    KEY idx_analysis_run_resource (resource_type, resource_id, created_at)
);
