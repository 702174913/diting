-- =====================================================================
-- 谛听 · S18 后台账号软删除（V5）
-- 背景：账号与审计日志强绑定（audit_log.actor_id 指向 admin_users.id）。
--       物理删除会让「每一次调取都可被审计」这句话失去引用，所以删除做成软删除：
--       标记 deleted_at、停用、并把用户名脱敏，行本身留着供审计追溯。
-- 语义：
--   - deleted_at 非空 = 已删除；列表默认不返回，登录与鉴权一律拒绝；
--   - 用户名脱敏为 deleted#<id>，既避免占用原用户名（可再次注册同名），
--     又不破坏 username 的唯一约束；
--   - 密码哈希一并换成随机值：即使有人在库里手工把 deleted_at 改回 NULL，
--     原密码也不能登录，必须走重置流程。
-- 幂等：列已存在时跳过；重复执行无副作用。
-- 回滚：V5__S18_account_soft_delete_rollback.sql
-- =====================================================================

DROP PROCEDURE IF EXISTS diting_s18_admin_soft_delete;
DELIMITER $$
CREATE PROCEDURE diting_s18_admin_soft_delete()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'admin_users'
          AND column_name = 'deleted_at'
    ) THEN
        ALTER TABLE admin_users
            ADD COLUMN deleted_at datetime(3) NULL COMMENT '软删除时间；非空即已删除（账号与审计强绑定，不做物理删除）'
            AFTER updated_at;
    END IF;

    -- 列表查询按 deleted_at IS NULL 过滤，补一个索引
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'admin_users'
          AND index_name = 'idx_admin_deleted'
    ) THEN
        CREATE INDEX idx_admin_deleted ON admin_users (deleted_at);
    END IF;

    INSERT IGNORE INTO schema_migrations (version, name)
    VALUES ('S18', '后台账号软删除：deleted_at + 用户名脱敏 + 密码随机化');

    SELECT
        (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'admin_users' AND column_name = 'deleted_at')
        AS deleted_at_column_ready;
END$$
DELIMITER ;

CALL diting_s18_admin_soft_delete();
DROP PROCEDURE diting_s18_admin_soft_delete;
