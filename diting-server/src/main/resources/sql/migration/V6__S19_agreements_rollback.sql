-- =====================================================================
-- 谛听 · S19 回滚：删除 agreements / agreement_consents 两张表。
-- 注意：回滚会连同 admin 后台对手协议正文的修改一起删除；
--       agreement_consents 是审计快照，回滚前如需留存请先备份。
-- =====================================================================

DROP TABLE IF EXISTS agreement_consents;
DROP TABLE IF EXISTS agreements;

DELETE FROM schema_migrations WHERE version = 'S19';

SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name IN ('agreements', 'agreement_consents'))
    AS agreement_tables_left;
