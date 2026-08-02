ALTER TABLE scan_task ADD COLUMN check_large_key BOOLEAN NOT NULL DEFAULT TRUE AFTER include_pattern;
ALTER TABLE scan_task ADD COLUMN check_no_ttl BOOLEAN NOT NULL DEFAULT TRUE AFTER check_large_key;
