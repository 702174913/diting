-- =====================================================================
-- 谛听 · 法律条款库回滚（对应 V13__legal_clauses.sql）
-- 注意：回滚会删除后台维护的法律条款与全部规则绑定关系，先备份再执行：
--         CREATE TABLE legal_clauses_bak_<date> AS SELECT * FROM legal_clauses;
--         CREATE TABLE rule_legal_clauses_bak_<date> AS SELECT * FROM rule_legal_clauses;
--       条款是业务数据，重放迁移不会恢复内容。
-- 幂等：可重复执行；对象不存在时跳过。
-- =====================================================================

DROP TABLE IF EXISTS rule_legal_clauses;
DROP TABLE IF EXISTS legal_clauses;

DELETE FROM schema_migrations WHERE version = 'V13';

SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name IN ('legal_clauses', 'rule_legal_clauses'))
    AS legal_clause_tables_left;
