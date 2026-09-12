ALTER TABLE sync_runtime
    ADD COLUMN target_fence_generation BIGINT NULL AFTER spool_bytes,
    ADD COLUMN fence_published_at DATETIME(3) NULL AFTER target_fence_generation,
    ADD COLUMN takeover_count INT NOT NULL DEFAULT 0 AFTER fence_published_at;
