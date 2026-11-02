-- Heartbeat for request-local executed governance runs (TTL / cleanup), mirroring V40:
-- governance jobs left the shared async_job queue, so a stale run heartbeat (with
-- started_at as the fallback via COALESCE) is the only crash signal; the recovery
-- worker fails such runs. Nothing takes them over automatically.
ALTER TABLE ttl_governance_run
    ADD COLUMN heartbeat_at TIMESTAMP(3) NULL AFTER completed_at;

ALTER TABLE cleanup_governance_run
    ADD COLUMN heartbeat_at TIMESTAMP(3) NULL AFTER completed_at;
