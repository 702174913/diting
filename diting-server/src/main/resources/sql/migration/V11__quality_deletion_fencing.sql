-- 先停写、核对真实库并备份；不要在旧工作者仍运行时升级。
ALTER TABLE deletion_jobs ADD COLUMN lease_token VARCHAR(36) NULL
  COMMENT '清理作业的本轮租约令牌；rearm 后旧工作者不得收尾';

INSERT INTO schema_migrations (version, name)
VALUES ('V11', '清理作业租约代际栅栏');
