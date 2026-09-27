-- =====================================================================
-- S06 状态迁移回滚：QUEUED → PENDING，TRANSCRIBING → ONGOING
-- 注意：只能回到旧值语义；已被后续流程推进的状态（PARSING 等）不在回滚范围内，
-- 回滚前请确认没有任务处于新枚举的中间状态。
-- =====================================================================

DROP PROCEDURE IF EXISTS diting_s06_status_rollback;
DELIMITER $$
CREATE PROCEDURE diting_s06_status_rollback()
BEGIN
    DECLARE affected INT DEFAULT 0;

    UPDATE recognition_tasks SET status = 'PENDING' WHERE status = 'QUEUED';
    SET affected = ROW_COUNT();
    UPDATE recognition_tasks SET status = 'ONGOING' WHERE status = 'TRANSCRIBING';
    SET affected = affected + ROW_COUNT();

    DELETE FROM schema_migrations WHERE version = 'S06';

    SELECT affected AS reverted_rows,
           (SELECT COUNT(*) FROM recognition_tasks
             WHERE status NOT IN ('PENDING','ONGOING','COMPLETED','FAILED','INVALID')) AS new_status_rows_left;
END$$
DELIMITER ;

CALL diting_s06_status_rollback();
DROP PROCEDURE diting_s06_status_rollback;
