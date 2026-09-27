-- =====================================================================
-- S18 后台账号软删除回滚（V5）
-- 注意：回滚只撤掉结构与迁移记录，**不会**把已脱敏的用户名与已随机化的密码还原 ——
--       那些信息在删除时就被有意抹掉了。已软删除的账号会重新出现在列表里，
--       但用户名已是 deleted#<id>、密码不可用，需要 ADMIN 手工改回。
-- =====================================================================

DROP PROCEDURE IF EXISTS diting_s18_admin_soft_delete_rollback;
DELIMITER $$
CREATE PROCEDURE diting_s18_admin_soft_delete_rollback()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'admin_users'
          AND index_name = 'idx_admin_deleted'
    ) THEN
        DROP INDEX idx_admin_deleted ON admin_users;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'admin_users'
          AND column_name = 'deleted_at'
    ) THEN
        ALTER TABLE admin_users DROP COLUMN deleted_at;
    END IF;

    DELETE FROM schema_migrations WHERE version = 'S18';

    SELECT COUNT(*) AS admin_rows_left FROM admin_users;
END$$
DELIMITER ;

CALL diting_s18_admin_soft_delete_rollback();
DROP PROCEDURE diting_s18_admin_soft_delete_rollback;
