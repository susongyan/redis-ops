-- Heartbeat for request-local executed runs (validation / risk scan / discovery).
-- A stale heartbeat (with started_at as the fallback via COALESCE) marks a run whose
-- executing instance died without finalizing it; the recovery worker fails such runs.
ALTER TABLE validation_run
    ADD COLUMN heartbeat_at TIMESTAMP(3) NULL AFTER finished_at;

ALTER TABLE scan_run
    ADD COLUMN heartbeat_at TIMESTAMP(3) NULL AFTER completed_at;

ALTER TABLE discovery_run
    ADD COLUMN heartbeat_at DATETIME(3) NULL AFTER started_at;
