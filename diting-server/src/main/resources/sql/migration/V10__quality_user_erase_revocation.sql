-- 先核对真实库/备份；与拦截器版本同时部署，旧 JWT 必须回库复核。
ALTER TABLE users ADD COLUMN erased_at DATETIME(3) NULL
  COMMENT '抹除确认时立即撤销该账号的所有既有登录令牌';

INSERT INTO schema_migrations (version, name)
VALUES ('V10', '抹除撤销用户登录态');
