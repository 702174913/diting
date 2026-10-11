-- =====================================================================
-- 谛听 · 法律条款库（V13）
-- 背景：规则库需要标明每条判读规则对应的法律/规范条款；报告命中时把
--       所绑条款作为「相关依据」展示（条款原文由后台维护）。
-- 语义：
--   - legal_clauses：条款主数据（法规名称 + 条款号 + 原文），停用不物理删除，
--     历史报告与已有绑定不因停用而消失；
--   - rule_legal_clauses：规则↔条款绑定，按 audit_rules.id（非版本）存储，
--     修改绑定实时生效、**不生成规则新版本**——已确认口径：绑定不随版本快照，
--     历史报告重读时看到的是当前绑定；
--   - 一条规则可绑定多个条款，sort_order 保留提交顺序。
-- 幂等：表已存在时跳过建表；台账 upsert。
-- 回滚：V13__legal_clauses_rollback.sql
-- 执行方式：mysql -h<host> -u<user> -D<db> < V13__legal_clauses.sql
-- =====================================================================

CREATE TABLE IF NOT EXISTS legal_clauses (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    law_name   VARCHAR(200) NOT NULL COMMENT '法律/规范名称，如：个人信息保护法',
    article_no VARCHAR(64)  NOT NULL COMMENT '条款号，如：第十三条',
    content    TEXT         NOT NULL COMMENT '条款原文（报告引用展示）',
    remark     VARCHAR(500) NULL COMMENT '备注（内部说明，不进报告）',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用不删除：已有绑定与历史报告引用不受影响',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_law_article (law_name, article_no),
    KEY idx_enabled (enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '法律条款库（V13）';

CREATE TABLE IF NOT EXISTS rule_legal_clauses (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    rule_id    BIGINT      NOT NULL COMMENT 'audit_rules.id（按规则而非版本绑定，实时生效）',
    clause_id  BIGINT      NOT NULL COMMENT 'legal_clauses.id',
    sort_order INT         NOT NULL DEFAULT 0 COMMENT '展示顺序（绑定时的提交顺序）',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_clause (rule_id, clause_id),
    KEY idx_clause (clause_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '规则↔法律条款绑定（V13）';

-- ---------------------------------------------------------------------
-- 迁移台账
-- ---------------------------------------------------------------------
INSERT INTO schema_migrations (version, name)
VALUES ('V13', '法律条款库：legal_clauses + 规则绑定 rule_legal_clauses')
ON DUPLICATE KEY UPDATE name = VALUES(name);
