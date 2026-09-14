ALTER TABLE sync_runtime
    ADD COLUMN worker_ip VARCHAR(255) NULL COMMENT 'IP reported by the runtime lease owner',
    ADD COLUMN worker_ip_runtime_id VARCHAR(128) NULL COMMENT 'Runtime that reported the IP; guards mixed-version takeover';
