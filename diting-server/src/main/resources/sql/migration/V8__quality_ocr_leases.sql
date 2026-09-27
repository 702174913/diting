-- 人工部署迁移；未在业务库执行。先备份，再在隔离环境验收。
-- 旧 RUNNING 行的 lease_until 为 NULL，可被恢复领取；旧工作者没有新 token，不能覆盖新结果。
ALTER TABLE task_assets
  ADD COLUMN ocr_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN ocr_lease_token VARCHAR(36) NULL,
  ADD COLUMN ocr_lease_until DATETIME(3) NULL,
  ADD KEY idx_ocr_recovery (task_id, ocr_status, ocr_lease_until);

INSERT INTO schema_migrations (version, name)
VALUES ('V8', '图片OCR租约令牌与有限重试');
