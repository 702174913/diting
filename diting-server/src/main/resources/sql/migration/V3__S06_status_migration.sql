-- =====================================================================
-- 谛听 · S06 任务状态枚举迁移（V3）
-- 背景：S01 保留旧状态值（PENDING/ONGOING），状态机与新枚举在 S06 与两端同步上线。
-- 映射：PENDING → QUEUED，ONGOING → TRANSCRIBING（与 TaskStatus.fromLegacy 一致）。
-- 幂等：只改旧值，重复执行无副作用；不改动 COMPLETED/FAILED/INVALID 等已有终态。
-- 回滚：V3__S06_status_migration_rollback.sql
-- =====================================================================

DROP PROCEDURE IF EXISTS diting_s06_status_upgrade;
DELIMITER $$
CREATE PROCEDURE diting_s06_status_upgrade()
BEGIN
    DECLARE affected INT DEFAULT 0;

    UPDATE recognition_tasks SET status = 'QUEUED' WHERE status = 'PENDING';
    SET affected = ROW_COUNT();

    UPDATE recognition_tasks SET status = 'TRANSCRIBING' WHERE status = 'ONGOING';
    SET affected = affected + ROW_COUNT();

    INSERT IGNORE INTO schema_migrations (version, name)
    VALUES ('S06', '任务状态枚举迁移：PENDING→QUEUED，ONGOING→TRANSCRIBING');

    SELECT affected AS migrated_rows,
           (SELECT COUNT(*) FROM recognition_tasks WHERE status IN ('PENDING', 'ONGOING')) AS legacy_rows_left;
END$$
DELIMITER ;

CALL diting_s06_status_upgrade();
DROP PROCEDURE diting_s06_status_upgrade;
