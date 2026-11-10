-- Big-key split observability (design doc §9): channel-level split statistics kept separate from
-- the RESTORE-path progress counters. split_keys/split_bytes = keys rebuilt element-by-element and
-- their wire payload size; split_skipped_* = keys dropped with a SPLIT_KEY_SKIPPED event.
ALTER TABLE sync_full_progress
    ADD COLUMN split_keys BIGINT NOT NULL DEFAULT 0 AFTER applied_bytes,
    ADD COLUMN split_bytes BIGINT NOT NULL DEFAULT 0 AFTER split_keys,
    ADD COLUMN split_skipped_keys BIGINT NOT NULL DEFAULT 0 AFTER split_bytes,
    ADD COLUMN split_skipped_bytes BIGINT NOT NULL DEFAULT 0 AFTER split_skipped_keys;
