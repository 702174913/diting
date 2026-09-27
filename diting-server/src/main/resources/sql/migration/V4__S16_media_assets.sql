-- =====================================================================
-- 谛听 · S16「图片上传与 OCR 判读」迁移（V4）
-- 规格来源：用户需求（2026-09-24）：一次最多 6 张图片；录音与图片可同时上传、也可单独上传；
--           图片先 OCR 出文字，再按违规规则判读；每次上传算一份额度。
-- 前置版本：V3__S06_status_migration.sql
-- 幂等策略：可重复执行。表用 CREATE TABLE IF NOT EXISTS，列/索引按 information_schema
--           存在性判断后再执行；已应用版本写入 schema_migrations。
-- 回滚脚本：V4__S16_media_assets_rollback.sql
-- 执行方式：mysql -h<host> -u<user> -D<db> < V4__S16_media_assets.sql
--           脚本含 DELIMITER 与存储过程，请用 mysql 命令行客户端执行。
-- 非破坏承诺：不删表、不删列、不改写历史业务数据值；历史任务一律视为 AUDIO。
-- =====================================================================

DELIMITER $$
DROP PROCEDURE IF EXISTS diting_s16_media_upgrade$$
CREATE PROCEDURE diting_s16_media_upgrade()
BEGIN
    -- -----------------------------------------------------------------
    -- 1. upload_sessions.media_kind：上传会话的媒体类型（AUDIO/IMAGE）
    --    决定格式白名单与 Content-Type；历史会话一律 AUDIO。
    -- -----------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_sessions'
                     AND COLUMN_NAME = 'media_kind') THEN
        ALTER TABLE upload_sessions
            ADD COLUMN media_kind VARCHAR(16) NOT NULL DEFAULT 'AUDIO'
                COMMENT 'AUDIO/IMAGE，签发时按文件名判定、complete 时按文件头复核' AFTER status;
    END IF;

    -- -----------------------------------------------------------------
    -- 2. recognition_tasks.media_kind：任务来源类型（AUDIO/IMAGE/MIXED）
    --    管线据此决定走转写、OCR 还是两者；历史任务一律 AUDIO。
    -- -----------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'media_kind') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN media_kind VARCHAR(16) NOT NULL DEFAULT 'AUDIO'
                COMMENT 'AUDIO/IMAGE/MIXED：决定走转写、OCR 还是两者' AFTER upload_id;
    END IF;

    -- -----------------------------------------------------------------
    -- 2.5 recognition_tasks.oss_object 允许为空：纯图片任务没有音频对象。
    --     （NOT NULL 会让「只传图片」的任务插不进去；历史数据不为空，放宽不影响既有行。）
    -- -----------------------------------------------------------------
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'oss_object' AND IS_NULLABLE = 'NO') THEN
        ALTER TABLE recognition_tasks
            MODIFY COLUMN oss_object VARCHAR(255) NULL COMMENT '音频对象 key；纯图片任务为空';
    END IF;

    -- -----------------------------------------------------------------
    -- 3. sentences.source_asset_id：图片 OCR 来的句子指向对应图片
    --    音频句子为空；保留期间清理与展示都按它回溯。
    -- -----------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND COLUMN_NAME = 'source_asset_id') THEN
        ALTER TABLE sentences
            ADD COLUMN source_asset_id BIGINT NULL
                COMMENT 'task_assets.id：图片 OCR 来源；音频句子为空' AFTER sequence;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND INDEX_NAME = 'idx_sentences_asset') THEN
        ALTER TABLE sentences ADD KEY idx_sentences_asset (source_asset_id);
    END IF;
END$$

DROP PROCEDURE IF EXISTS diting_s16_task_assets_table$$
CREATE PROCEDURE diting_s16_task_assets_table()
BEGIN
    -- -----------------------------------------------------------------
    -- 4. task_assets：一条任务下的音频/图片资产（含 OCR 状态与结果）
    --    - 一条任务最多 1 个 AUDIO + 6 个 IMAGE（业务侧校验，库层只保证顺序唯一）；
    --    - sequence 在任务内唯一：音频固定 0，图片从 1 开始按用户选择顺序；
    --    - ocr_text 保留原始识别文本，便于审计与「这句话来自哪张图」的回溯。
    -- -----------------------------------------------------------------
    CREATE TABLE IF NOT EXISTS task_assets (
        id            BIGINT       NOT NULL AUTO_INCREMENT,
        task_id       BIGINT       NOT NULL COMMENT 'recognition_tasks.id',
        upload_id     BIGINT       NULL COMMENT 'upload_sessions.id（提交时被消费）',
        kind          VARCHAR(16)  NOT NULL COMMENT 'AUDIO/IMAGE',
        sequence      INT          NOT NULL COMMENT '任务内顺序：音频 0，图片 1..6',
        object_key    VARCHAR(255) NOT NULL COMMENT '对象存储 key，服务端生成',
        filename      VARCHAR(255) NOT NULL COMMENT '展示用文件名',
        media_format  VARCHAR(32)  NULL COMMENT '服务端嗅探出的真实格式',
        size_bytes    BIGINT       NULL COMMENT '对象实际大小',
        ocr_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
            COMMENT 'PENDING/RUNNING/DONE/EMPTY/FAILED；音频资产恒为 SKIPPED',
        ocr_text      MEDIUMTEXT   NULL COMMENT 'OCR 原始 Data（JSON 字符串，含 content 与文字块），音频为空',
        ocr_error     VARCHAR(200) NULL COMMENT '失败原因（不含原文与凭据）',
        object_purged TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '对象是否已清理（到期/删除）',
        created_at    DATETIME(3)  NOT NULL,
        ocr_done_at   DATETIME(3)  NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_task_sequence (task_id, sequence),
        KEY idx_task_kind (task_id, kind),
        KEY idx_ocr_status (ocr_status)
    ) ENGINE = InnoDB
      DEFAULT CHARSET = utf8mb4
      COLLATE = utf8mb4_0900_ai_ci COMMENT '任务资产：音频与图片（S16）';
END$$

-- =====================================================================
-- 执行：两块存储过程都改完后再调用，最后恢复默认分隔符
-- =====================================================================
CALL diting_s16_media_upgrade()$$
CALL diting_s16_task_assets_table()$$

DROP PROCEDURE IF EXISTS diting_s16_media_upgrade$$
DROP PROCEDURE IF EXISTS diting_s16_task_assets_table$$
DELIMITER ;

-- ---------------------------------------------------------------------
-- 5. 迁移台账
-- ---------------------------------------------------------------------
INSERT INTO schema_migrations (version, name)
VALUES ('V4', 'S16 图片上传与 OCR：媒体类型字段 + task_assets 表 + 句子来源资产')
ON DUPLICATE KEY UPDATE name = VALUES(name);
