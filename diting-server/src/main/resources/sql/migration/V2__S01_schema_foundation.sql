-- =====================================================================
-- 谛听 · S01「数据保护与增量迁移」主迁移脚本（V2）
-- 规格来源：docs/开发文档.md §6 数据字典（目标模型）
-- 前置版本：../schema.sql（V1 基线：users / recognition_tasks / sentences）
-- 幂等策略：可重复执行。表用 CREATE TABLE IF NOT EXISTS，列/索引/约束按
--           information_schema 存在性判断后再执行；已应用版本写入 schema_migrations。
-- 回滚脚本：V2__S01_rollback.sql
-- 执行方式：mysql -h<host> -u<user> -D<db> < V2__S01_schema_foundation.sql
--           脚本含 DELIMITER 与存储过程，请用 mysql 命令行客户端执行。
-- 非破坏承诺：不删表、不删列、不改写历史业务数据值。
--           recognition_tasks.status 的旧值（PENDING/ONGOING）转换属于 S06
--           状态机与两端同步的工作，本轮只加宽容度（VARCHAR(16)→VARCHAR(32)）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 迁移台账（重复迁移保护）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    VARCHAR(32)  NOT NULL COMMENT '迁移版本号',
    name       VARCHAR(128) NOT NULL COMMENT '迁移说明',
    applied_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '应用时间',
    PRIMARY KEY (version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '迁移执行台账（幂等保护）';

-- ---------------------------------------------------------------------
-- 1. 新表：开发文档 §6.2 上传、角色、作业
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_sessions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL COMMENT '归属用户，取自 token，不接受客户端传入',
    object_key      VARCHAR(255) NOT NULL COMMENT '服务端生成的 OSS 对象 key，不含用户可控路径',
    status          VARCHAR(24)  NOT NULL DEFAULT 'SIGNED' COMMENT 'SIGNED/VERIFIED/CONSUMED',
    filename        VARCHAR(255) NOT NULL COMMENT '客户端原始文件名，仅用于展示',
    size_bytes      BIGINT       NULL COMMENT 'complete 阶段以对象实际长度为准',
    duration_ms     BIGINT       NULL COMMENT '服务端检测的真实时长',
    media_format    VARCHAR(32)  NULL COMMENT '服务端检测的真实格式，不信任扩展名',
    content_hash    VARCHAR(128) NULL COMMENT '对象内容哈希（云端校验用）',
    consent_version VARCHAR(32)  NOT NULL COMMENT '用户同意的条款版本',
    consented_at    DATETIME(3)  NOT NULL COMMENT '同意时间',
    signed_until    DATETIME(3)  NOT NULL COMMENT '签名有效期',
    cleanup_after   DATETIME(3)  NOT NULL COMMENT '孤立对象清理时间',
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_key (object_key),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'OSS 预签名上传会话（§6.2）';

CREATE TABLE IF NOT EXISTS speaker_mappings (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    task_id          BIGINT      NOT NULL COMMENT 'recognition_tasks.id',
    speaker_id       INT         NOT NULL COMMENT '听悟声学聚类标签',
    role             VARCHAR(16) NOT NULL COMMENT 'AGENT/CUSTOMER/UNKNOWN，未知不强制分配',
    revision         BIGINT      NOT NULL COMMENT '角色映射版本，从 1 起递增',
    confirmed_by     BIGINT      NULL COMMENT '确认者 ID',
    confirmed_domain VARCHAR(16) NULL COMMENT 'USER/ADMIN',
    confirmed_at     DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_rev_speaker (task_id, revision, speaker_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '说话人角色映射（§6.2）';

CREATE TABLE IF NOT EXISTS deletion_jobs (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL COMMENT '发起删除的用户',
    target_type       VARCHAR(24) NOT NULL COMMENT 'TASK/ERASE 等删除目标类型',
    target_id         BIGINT      NOT NULL COMMENT '目标 ID（TASK 时为 recognition_tasks.id）',
    status            VARCHAR(24) NOT NULL COMMENT 'QUEUED/RUNNING/RETRY_WAIT/COMPLETED/FAILED',
    remaining_objects JSON        NULL COMMENT '待清理对象清单，仅保留清理必需数据',
    attempt_count     INT         NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(3) NULL,
    error_code        VARCHAR(64) NULL COMMENT '安全错误码，不含原文/签名 URL',
    created_at        DATETIME(3) NOT NULL,
    finished_at       DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_target (target_type, target_id),
    KEY idx_status_next (status, next_attempt_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '删除/清理作业（§6.2）';

-- ---------------------------------------------------------------------
-- 2. 新表：开发文档 §6.3 规则与报告
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_rules (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    code            VARCHAR(64) NOT NULL COMMENT '规则业务编码',
    current_version INT         NOT NULL COMMENT '当前生效版本号',
    enabled         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '停用不删除历史版本',
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '规则主表（§6.3）';

CREATE TABLE IF NOT EXISTS audit_rule_versions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id           BIGINT       NOT NULL COMMENT 'audit_rules.id',
    version           INT          NOT NULL,
    group_name        VARCHAR(100) NOT NULL COMMENT '模型组/规则分组名',
    name              VARCHAR(200) NOT NULL,
    description       TEXT         NOT NULL COMMENT '平台规则说明，不写法律结论',
    match_type        VARCHAR(16)  NOT NULL COMMENT 'hit/require/must-have',
    role_scope        VARCHAR(16)  NOT NULL COMMENT '约束角色范围',
    trigger_role      VARCHAR(16)  NULL COMMENT 'require 的触发方，hit/must-have 可空',
    keywords          JSON         NOT NULL COMMENT '触发词/关键词数组',
    expected_keywords JSON         NOT NULL COMMENT 'require 的应答词数组，非 require 存空数组',
    window_size       INT          NULL COMMENT 'require 窗口：后续目标角色发言条数',
    deduct            INT          NOT NULL COMMENT '扣分 0—100',
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_version (rule_id, version),
    CONSTRAINT chk_rule_deduct CHECK (deduct BETWEEN 0 AND 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '规则版本快照（§6.3）';

CREATE TABLE IF NOT EXISTS rule_sets (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    version          BIGINT      NOT NULL COMMENT '规则集合版本',
    rule_version_ids JSON        NOT NULL COMMENT '本次分析启用的 audit_rule_versions.id 列表',
    created_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version (version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '一次分析所用的规则集合快照（§6.3）';

CREATE TABLE IF NOT EXISTS report_versions (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    task_id            BIGINT      NOT NULL COMMENT 'recognition_tasks.id',
    version            BIGINT      NOT NULL COMMENT '报告版本，从 1 起递增',
    rule_set_version   BIGINT      NOT NULL COMMENT 'rule_sets.version',
    mapping_revision   BIGINT      NOT NULL COMMENT 'speaker_mappings.revision',
    risk_score         INT         NULL COMMENT '发布后必填；min(100,去重扣分总和)',
    compliance_score   INT         NULL COMMENT '发布后必填；100-risk_score',
    threshold_snapshot INT         NOT NULL COMMENT '阈值快照 0—100',
    config_version     BIGINT      NOT NULL COMMENT 'app_config.version 快照',
    status             VARCHAR(24) NOT NULL COMMENT 'ANALYZING/PUBLISHED/FAILED',
    published_at       DATETIME(3) NULL,
    created_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_version (task_id, version),
    KEY idx_task_status (task_id, status),
    CONSTRAINT chk_report_score_range CHECK (
        (risk_score IS NULL OR risk_score BETWEEN 0 AND 100)
        AND (compliance_score IS NULL OR compliance_score BETWEEN 0 AND 100)
        AND threshold_snapshot BETWEEN 0 AND 100
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '版本化报告（§6.3）';

CREATE TABLE IF NOT EXISTS violations (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    report_version_id   BIGINT       NOT NULL COMMENT 'report_versions.id',
    rule_version_id     BIGINT       NOT NULL COMMENT 'audit_rule_versions.id',
    sentence_id         BIGINT       NULL COMMENT '通话级缺失（must-have）允许为空',
    trigger_sentence_id BIGINT       NULL COMMENT 'require 的触发句',
    evidence_sentence_ids JSON       NOT NULL COMMENT '证据句 ID 数组',
    ranges              JSON         NOT NULL COMMENT '[{sentenceId,start,end}]，原文 UTF-16 偏移，左闭右开',
    deduct              INT          NOT NULL COMMENT '本项扣分 0—100',
    note                TEXT         NOT NULL COMMENT '说明，引用规则版本，不做法律保证',
    evidence_key        VARCHAR(128) NOT NULL COMMENT '去重键：同规则同证据只记一次',
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence (report_version_id, rule_version_id, evidence_key),
    KEY idx_sentence (sentence_id),
    CONSTRAINT chk_violation_deduct CHECK (deduct BETWEEN 0 AND 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '规则命中证据（§6.3）';

-- ---------------------------------------------------------------------
-- 3. 新表：开发文档 §6.4 配置、额度、后台、导出
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_config (
    config_key   VARCHAR(64)  NOT NULL COMMENT '配置键',
    config_value VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL COMMENT '三个配置作为同一版本快照保存',
    remark       VARCHAR(255) NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '产品策略配置（§6.4）';

CREATE TABLE IF NOT EXISTS daily_quota (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    business_date DATE        NOT NULL COMMENT 'Asia/Shanghai 业务日期',
    reserved      INT         NOT NULL DEFAULT 0,
    consumed      INT         NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, business_date),
    CONSTRAINT chk_quota_nonneg CHECK (reserved >= 0 AND consumed >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '每日额度计数（§6.4）';

CREATE TABLE IF NOT EXISTS quota_ledger (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    task_id       BIGINT      NOT NULL COMMENT 'recognition_tasks.id，UK 防重复扣费',
    business_date DATE        NOT NULL,
    state         VARCHAR(16) NOT NULL COMMENT 'RESERVED/CONSUMED/RELEASED',
    created_at    DATETIME(3) NOT NULL,
    updated_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '额度流水（§6.4）';

CREATE TABLE IF NOT EXISTS admin_users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt，不写默认弱密码',
    nickname      VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN/OPERATOR/INVESTOR',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '后台账号（§6.4）';

CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    actor_domain VARCHAR(16) NOT NULL COMMENT 'USER/ADMIN/SYSTEM',
    actor_id     BIGINT      NULL,
    action       VARCHAR(64) NOT NULL COMMENT '动作码',
    target_type  VARCHAR(32) NOT NULL,
    target_id    VARCHAR(64) NULL,
    result       VARCHAR(24) NOT NULL COMMENT 'SUCCESS/DENIED/FAILED',
    detail       JSON        NULL COMMENT '仅元数据，禁止秘密与对话原文',
    ip           VARCHAR(45) NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_actor_created (actor_id, created_at),
    KEY idx_target_created (target_type, target_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '审计日志（§6.4）';

CREATE TABLE IF NOT EXISTS export_jobs (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id          BIGINT       NOT NULL,
    owner_domain      VARCHAR(16)  NOT NULL COMMENT 'USER/ADMIN，两个身份域不共用 token',
    report_version_id BIGINT       NOT NULL COMMENT 'report_versions.id',
    with_audio        TINYINT(1)   NOT NULL DEFAULT 0,
    status            VARCHAR(24)  NOT NULL COMMENT 'QUEUED/RUNNING/COMPLETED/FAILED',
    artifact_key      VARCHAR(255) NULL COMMENT '产物发布前必填',
    expires_at        DATETIME(3)  NOT NULL COMMENT '产物有效期',
    attempt_count     INT          NOT NULL DEFAULT 0,
    error_code        VARCHAR(64)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    finished_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_owner_created (owner_domain, owner_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'PDF/ZIP 导出作业（§6.4）';

-- ---------------------------------------------------------------------
-- 4. 既有表增量：recognition_tasks
--    先加列 → 先回填 → 后加唯一键 → 最后删除被覆盖的旧索引（失败时不留半删状态）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS diting_s01_alter_tasks;
DROP PROCEDURE IF EXISTS diting_s01_sentences_add_columns;
DROP PROCEDURE IF EXISTS diting_s01_backfill_sentences;
DROP PROCEDURE IF EXISTS diting_s01_sentences_add_constraints;
DROP PROCEDURE IF EXISTS diting_s01_alter_users;
DELIMITER $$

CREATE PROCEDURE diting_s01_alter_tasks()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'upload_id') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN upload_id BIGINT NULL COMMENT 'upload_sessions.id' AFTER user_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'idempotency_key') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN idempotency_key VARCHAR(64) NULL COMMENT '客户端幂等键，历史数据为空' AFTER upload_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'version') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（租约/CAS）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'attempt_count') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试次数';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'lease_until') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN lease_until DATETIME(3) NULL COMMENT '作业租约到期时间';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'next_attempt_at') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN next_attempt_at DATETIME(3) NULL COMMENT '下次可领取时间（退避）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'error_code') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN error_code VARCHAR(64) NULL COMMENT '安全错误码，不存敏感响应';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'expires_at') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN expires_at DATETIME(3) NULL COMMENT '原件保留截止（上传时间+30天）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'audio_purged') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN audio_purged TINYINT(1) NOT NULL DEFAULT 0 COMMENT '原件是否已清理';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND COLUMN_NAME = 'deleted_at') THEN
        ALTER TABLE recognition_tasks
            ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '墓碑时间，非空即不可再访问';
    END IF;

    -- status 放宽到 VARCHAR(32)：新状态枚举（TRANSCRIBING/WAITING_ROLE 等）超出原宽度
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'status' AND CHARACTER_MAXIMUM_LENGTH < 32) THEN
        ALTER TABLE recognition_tasks
            MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
            COMMENT '任务状态；旧值 PENDING/ONGOING 由 S06 状态机按迁移规则转换';
    END IF;

    -- oss_object 放宽为可空：新流程以 upload_sessions.object_key 为可信来源
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'oss_object' AND IS_NULLABLE = 'NO') THEN
        ALTER TABLE recognition_tasks
            MODIFY COLUMN oss_object VARCHAR(255) NULL COMMENT 'OSS 对象 key（历史字段，新流程见 upload_id）';
    END IF;

    -- 时间列精度对齐目标模型 DATETIME(3)
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'created_at' AND DATETIME_PRECISION = 0) THEN
        ALTER TABLE recognition_tasks
            MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND COLUMN_NAME = 'finished_at' AND DATETIME_PRECISION = 0) THEN
        ALTER TABLE recognition_tasks
            MODIFY COLUMN finished_at DATETIME(3) NULL;
    END IF;

    -- 新增唯一键与索引（先加后删，避免中途失败留下“只删不加”）
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_user_idem') THEN
        ALTER TABLE recognition_tasks
            ADD UNIQUE KEY uk_user_idem (user_id, idempotency_key);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_upload') THEN
        ALTER TABLE recognition_tasks
            ADD UNIQUE KEY uk_upload (upload_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'uk_task') THEN
        ALTER TABLE recognition_tasks
            ADD UNIQUE KEY uk_task (task_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_user_created') THEN
        ALTER TABLE recognition_tasks
            ADD KEY idx_user_created (user_id, created_at, id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_queue') THEN
        ALTER TABLE recognition_tasks
            ADD KEY idx_queue (status, next_attempt_at, lease_until);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                     AND INDEX_NAME = 'idx_expiry') THEN
        ALTER TABLE recognition_tasks
            ADD KEY idx_expiry (expires_at, audio_purged);
    END IF;

    -- 被新索引覆盖的旧索引：idx_task ⊂ uk_task，idx_user ⊂ idx_user_created
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND INDEX_NAME = 'idx_task') THEN
        ALTER TABLE recognition_tasks DROP INDEX idx_task;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recognition_tasks'
                 AND INDEX_NAME = 'idx_user') THEN
        ALTER TABLE recognition_tasks DROP INDEX idx_user;
    END IF;
END$$

-- sentences：先加可空列 → 回填 → 收 NOT NULL → 加唯一键 → 清理旧索引
-- 注意：建列必须早于回填，回填必须早于唯一键，顺序不可调换。
CREATE PROCEDURE diting_s01_sentences_add_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND COLUMN_NAME = 'sequence') THEN
        ALTER TABLE sentences
            ADD COLUMN sequence INT NULL COMMENT '句序，task 内唯一，迁移后非空' AFTER task_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND COLUMN_NAME = 'words') THEN
        ALTER TABLE sentences
            ADD COLUMN words JSON NULL COMMENT '词级时间戳明细，可为空';
    END IF;
END$$

CREATE PROCEDURE diting_s01_backfill_sentences()
BEGIN
    -- 句序按（start_ms, id）确定性生成，只补空值，不覆盖已有值
    UPDATE sentences s
        JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY start_ms, id) AS rn
              FROM sentences) x ON x.id = s.id
    SET s.sequence = x.rn
    WHERE s.sequence IS NULL;
END$$

CREATE PROCEDURE diting_s01_sentences_add_constraints()
BEGIN
    -- 回填完成后才收紧为 NOT NULL
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                 AND COLUMN_NAME = 'sequence' AND IS_NULLABLE = 'YES') THEN
        ALTER TABLE sentences
            MODIFY COLUMN sequence INT NOT NULL COMMENT '句序，task 内唯一';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND INDEX_NAME = 'uk_task_seq') THEN
        ALTER TABLE sentences
            ADD UNIQUE KEY uk_task_seq (task_id, sequence);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND INDEX_NAME = 'idx_task_start') THEN
        ALTER TABLE sentences
            ADD KEY idx_task_start (task_id, start_ms);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                     AND CONSTRAINT_NAME = 'chk_sentences_time') THEN
        ALTER TABLE sentences
            ADD CONSTRAINT chk_sentences_time CHECK (start_ms <= end_ms);
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sentences'
                 AND INDEX_NAME = 'idx_task') THEN
        ALTER TABLE sentences DROP INDEX idx_task;
    END IF;
END$$

CREATE PROCEDURE diting_s01_alter_users()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
                 AND COLUMN_NAME = 'created_at' AND DATETIME_PRECISION = 0) THEN
        ALTER TABLE users
            MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
    END IF;
END$$
DELIMITER ;

CALL diting_s01_alter_tasks();
CALL diting_s01_sentences_add_columns();
CALL diting_s01_backfill_sentences();
CALL diting_s01_sentences_add_constraints();
CALL diting_s01_alter_users();

DROP PROCEDURE diting_s01_alter_tasks;
DROP PROCEDURE diting_s01_sentences_add_columns;
DROP PROCEDURE diting_s01_backfill_sentences;
DROP PROCEDURE diting_s01_sentences_add_constraints;
DROP PROCEDURE diting_s01_alter_users;

-- ---------------------------------------------------------------------
-- 5. 台账登记（重复执行只保留一行）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO schema_migrations (version, name)
VALUES ('S01', '数据基础增量迁移：14 张新表 + 既有三表非破坏增列/回填/约束');

SELECT version AS applied_version, name AS migration_name, applied_at
FROM schema_migrations
ORDER BY applied_at;
