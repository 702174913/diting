-- 人工执行；当前运行版本依赖 V9，执行前按 docs/quality-fix-2026-09-26.md 备份和预检。
ALTER TABLE export_jobs
  ADD COLUMN lease_token VARCHAR(36) NULL,
  ADD COLUMN lease_until DATETIME(3) NULL,
  ADD COLUMN next_attempt_at DATETIME(3) NULL,
  ADD KEY idx_export_due (status, next_attempt_at, lease_until);

INSERT INTO schema_migrations (version, name)
VALUES ('V9', '导出作业领取栅栏与故障恢复');
