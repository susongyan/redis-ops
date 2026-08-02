ALTER TABLE risk_finding ADD COLUMN key_name VARCHAR(1024) NULL AFTER risk_level;
CREATE INDEX idx_risk_finding_key_name ON risk_finding (key_name(128));
