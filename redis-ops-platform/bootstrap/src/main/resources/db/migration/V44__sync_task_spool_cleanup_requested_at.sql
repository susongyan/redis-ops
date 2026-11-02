ALTER TABLE sync_task
    ADD COLUMN spool_cleanup_requested_at DATETIME(3) NULL COMMENT 'Non-NULL asks every sync worker to delete its local spool directory for this task' AFTER finished_at;
