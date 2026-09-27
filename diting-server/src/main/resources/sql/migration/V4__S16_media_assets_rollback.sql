-- =====================================================================
-- 谛听 · S16 迁移回滚脚本（对应 V4__S16_media_assets.sql）
-- 作用：把库恢复到 V3 后的状态（不含 media_kind / task_assets / source_asset_id）
-- 幂等：可重复执行；对象不存在时跳过
-- 安全护栏：task_assets 只要已有数据（说明已经有人传过图片），回滚默认中止——
--           直接删表会让图片与 OCR 结果失去引用。确认丢弃时先执行：
--             SET @diting_s16_force_rollback = 1;
-- 执行方式：mysql -h<host> -u<user> -D<db> < V4__S16_media_assets_rollback.sql
-- 说明：本脚本只回滚结构，不改写任何历史业务数据值；被删列（media_kind/
--       source_asset_id）是 S16 新增列，回滚会丢失其取值，属预期行为。
-- =====================================================================

DELIMITER $$
DROP PROCEDURE IF EXISTS diting_s16_rollback$$

CREATE PROCEDURE diting_s16_rollback()
BEGIN
    DECLARE v_rows BIGINT DEFAULT 0;
    DECLARE v_force INT DEFAULT 0;

    SET v_force = IFNULL(@diting_s16_force_rollback, 0);

    -- ---------- 护栏：资产表有数据则中止 ----------
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_assets') THEN
        SELECT COUNT(*) INTO v_rows FROM task_assets;
        IF v_rows > 0 AND v_force = 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'task_assets 已有数据，回滚中止：确认丢弃请先 SET @diting_s16_force_rollback = 1';
        END IF;
    END IF;

    -- ---------- 6. sentences.source_asset_id ----------
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                 AND INDEX_NAME = 'idx_sentences_asset') THEN
        ALTER TABLE sentences DROP INDEX idx_sentences_asset;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                 AND COLUMN_NAME = 'source_asset_id') THEN
        ALTER TABLE sentences DROP COLUMN source_asset_id;
    END IF;

    -- ---------- 5. task_assets ----------
    DROP TABLE IF EXISTS task_assets;

    -- ---------- 4. recognition_tasks.media_kind ----------
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'media_kind') THEN
        ALTER TABLE recognition_tasks DROP COLUMN media_kind;
    END IF;

    -- ---------- 3.5 recognition_tasks.oss_object 恢复 NOT NULL ----------
    -- 只有确认没有「纯图片任务」留下的空值时才收紧，否则回滚中止（避免把 NULL 变成 ''）
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'oss_object' AND IS_NULLABLE = 'YES') THEN
        IF EXISTS (SELECT 1 FROM recognition_tasks WHERE oss_object IS NULL) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = '存在 oss_object 为空的纯图片任务，回滚中止：先清理这些任务';
        END IF;
        ALTER TABLE recognition_tasks
            MODIFY COLUMN oss_object VARCHAR(255) NOT NULL COMMENT 'OSS 对象 key';
    END IF;

    -- ---------- 3. upload_sessions.media_kind ----------
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_sessions'
                 AND COLUMN_NAME = 'media_kind') THEN
        ALTER TABLE upload_sessions DROP COLUMN media_kind;
    END IF;

    -- ---------- 2. 台账 ----------
    DELETE FROM schema_migrations WHERE version = 'V4';
END$$

CALL diting_s16_rollback()$$
DROP PROCEDURE IF EXISTS diting_s16_rollback$$
DELIMITER ;

SELECT 'V4 回滚完成：media_kind 已移除，task_assets 已删除' AS result;
