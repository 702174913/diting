-- =====================================================================
-- 谛听 · S01 迁移回滚脚本（对应 V2__S01_schema_foundation.sql）
-- 作用：把库恢复到 V1 基线（users / recognition_tasks / sentences 三表原状）
-- 幂等：可重复执行；对象不存在时跳过
-- 安全护栏：
--   1) S01 新表只要已有数据，回滚默认中止（避免误删进入下一步骤的业务数据）。
--      确认丢弃时先执行：SET @diting_s01_force_rollback = 1;
--   2) 若已出现 oss_object 为空的新任务，无法恢复 NOT NULL 约束，回滚中止。
-- 执行方式：mysql -h<host> -u<user> -D<db> < V2__S01_rollback.sql
-- 说明：本脚本只回滚结构，不改写任何历史业务数据值；被删列（sequence/words/
--      租约字段等）是 S01 新增列，回滚会丢失其内容，属预期行为。
-- =====================================================================

DROP PROCEDURE IF EXISTS diting_s01_rollback;
DELIMITER $$

CREATE PROCEDURE diting_s01_rollback()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_tbl VARCHAR(64);
    DECLARE v_new_rows BIGINT DEFAULT 0;
    DECLARE v_null_oss BIGINT DEFAULT 0;
    DECLARE v_left BIGINT DEFAULT 0;
    DECLARE v_msg VARCHAR(200);

    -- ---------- 护栏 1：新表有数据则中止 ----------
    DECLARE cur CURSOR FOR
        SELECT TABLE_NAME
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME NOT IN ('users', 'recognition_tasks', 'sentences', 'schema_migrations');
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_tbl;
        IF v_done = 1 THEN
            LEAVE read_loop;
        END IF;
        SET @diting_sql = CONCAT('SELECT COUNT(*) INTO @diting_cnt FROM `', v_tbl, '`');
        PREPARE stmt FROM @diting_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        SET v_new_rows = v_new_rows + IFNULL(@diting_cnt, 0);
    END LOOP;
    CLOSE cur;

    IF v_new_rows > 0 AND IFNULL(@diting_s01_force_rollback, 0) <> 1 THEN
        SET v_msg = CONCAT('回滚已中止：S01 新表已有 ', v_new_rows,
                           ' 行数据。确认丢弃请先 SET @diting_s01_force_rollback = 1; 再重跑回滚。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- ---------- 护栏 2：无法恢复 NOT NULL 约束的情形 ----------
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'upload_id') THEN
        SELECT COUNT(*) INTO v_null_oss FROM recognition_tasks WHERE oss_object IS NULL;
        IF v_null_oss > 0 THEN
            SET v_msg = CONCAT('回滚已中止：有 ', v_null_oss,
                               ' 条任务的 oss_object 为空，无法恢复 V1 的 NOT NULL 约束。');
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
        END IF;
    END IF;

    -- ---------- 1. 新索引先删，再删新增列（避免删除被引用的列） ----------
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks') THEN

        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_queue') THEN
            ALTER TABLE recognition_tasks DROP INDEX idx_queue;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_expiry') THEN
            ALTER TABLE recognition_tasks DROP INDEX idx_expiry;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_user_created') THEN
            ALTER TABLE recognition_tasks DROP INDEX idx_user_created;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_user_idem') THEN
            ALTER TABLE recognition_tasks DROP INDEX uk_user_idem;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_upload') THEN
            ALTER TABLE recognition_tasks DROP INDEX uk_upload;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_task') THEN
            ALTER TABLE recognition_tasks DROP INDEX uk_task;
        END IF;

        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'deleted_at') THEN
            ALTER TABLE recognition_tasks DROP COLUMN deleted_at;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'audio_purged') THEN
            ALTER TABLE recognition_tasks DROP COLUMN audio_purged;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'expires_at') THEN
            ALTER TABLE recognition_tasks DROP COLUMN expires_at;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'error_code') THEN
            ALTER TABLE recognition_tasks DROP COLUMN error_code;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'next_attempt_at') THEN
            ALTER TABLE recognition_tasks DROP COLUMN next_attempt_at;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'lease_until') THEN
            ALTER TABLE recognition_tasks DROP COLUMN lease_until;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'attempt_count') THEN
            ALTER TABLE recognition_tasks DROP COLUMN attempt_count;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'version') THEN
            ALTER TABLE recognition_tasks DROP COLUMN version;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'idempotency_key') THEN
            ALTER TABLE recognition_tasks DROP COLUMN idempotency_key;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'upload_id') THEN
            ALTER TABLE recognition_tasks DROP COLUMN upload_id;
        END IF;

        -- 恢复 V1 列定义
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'status' AND CHARACTER_MAXIMUM_LENGTH <> 16) THEN
            ALTER TABLE recognition_tasks
                MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'oss_object' AND IS_NULLABLE = 'YES') THEN
            ALTER TABLE recognition_tasks
                MODIFY COLUMN oss_object VARCHAR(255) NOT NULL;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'created_at' AND DATETIME_PRECISION <> 0) THEN
            ALTER TABLE recognition_tasks
                MODIFY COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'finished_at' AND DATETIME_PRECISION <> 0) THEN
            ALTER TABLE recognition_tasks
                MODIFY COLUMN finished_at DATETIME NULL;
        END IF;

        -- 恢复 V1 索引
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                         AND INDEX_NAME = 'idx_user') THEN
            ALTER TABLE recognition_tasks ADD KEY idx_user (user_id);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                         AND INDEX_NAME = 'idx_task') THEN
            ALTER TABLE recognition_tasks ADD KEY idx_task (task_id);
        END IF;
    END IF;

    -- ---------- 2. sentences ----------
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences') THEN

        IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND CONSTRAINT_NAME = 'chk_sentences_time') THEN
            ALTER TABLE sentences DROP CONSTRAINT chk_sentences_time;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND INDEX_NAME = 'uk_task_seq') THEN
            ALTER TABLE sentences DROP INDEX uk_task_seq;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND INDEX_NAME = 'idx_task_start') THEN
            ALTER TABLE sentences DROP INDEX idx_task_start;
        END IF;

        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND COLUMN_NAME = 'words') THEN
            ALTER TABLE sentences DROP COLUMN words;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND COLUMN_NAME = 'sequence') THEN
            ALTER TABLE sentences DROP COLUMN sequence;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                         AND INDEX_NAME = 'idx_task') THEN
            ALTER TABLE sentences ADD KEY idx_task (task_id);
        END IF;
    END IF;

    -- ---------- 3. users ----------
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                 AND COLUMN_NAME = 'created_at' AND DATETIME_PRECISION <> 0) THEN
        ALTER TABLE users
            MODIFY COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
    END IF;

    -- ---------- 4. 删除 S01 新建表 ----------
    DROP TABLE IF EXISTS export_jobs;
    DROP TABLE IF EXISTS audit_log;
    DROP TABLE IF EXISTS admin_users;
    DROP TABLE IF EXISTS quota_ledger;
    DROP TABLE IF EXISTS daily_quota;
    DROP TABLE IF EXISTS app_config;
    DROP TABLE IF EXISTS violations;
    DROP TABLE IF EXISTS report_versions;
    DROP TABLE IF EXISTS rule_sets;
    DROP TABLE IF EXISTS audit_rule_versions;
    DROP TABLE IF EXISTS audit_rules;
    DROP TABLE IF EXISTS deletion_jobs;
    DROP TABLE IF EXISTS speaker_mappings;
    DROP TABLE IF EXISTS upload_sessions;

    -- ---------- 5. 台账清理 ----------
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schema_migrations') THEN
        DELETE FROM schema_migrations WHERE version = 'S01';
        SELECT COUNT(*) INTO v_left FROM schema_migrations;
        IF v_left = 0 THEN
            DROP TABLE schema_migrations;
        END IF;
    END IF;

    SELECT 'S01 rollback done' AS result;
END$$
DELIMITER ;

CALL diting_s01_rollback();
DROP PROCEDURE diting_s01_rollback;
