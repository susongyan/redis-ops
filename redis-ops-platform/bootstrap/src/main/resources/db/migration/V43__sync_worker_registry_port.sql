ALTER TABLE sync_worker_registry
    ADD COLUMN worker_port INT NULL COMMENT 'HTTP port last reported by this instance name; links to /actuator/health' AFTER worker_ip;
