-- =====================================================================
-- 谛听 · 演示样本回滚（S18）  精确删除 gen_demo_seed.py 造的那批行
-- 只按标记删：id 1001—1999 + seed 标记，不触碰真实数据与既有测试行
-- =====================================================================
SET NAMES utf8mb4;
START TRANSACTION;
DELETE v FROM violations v JOIN report_versions rv ON rv.id = v.report_version_id WHERE rv.id BETWEEN 1001 AND 1999;
DELETE FROM report_versions WHERE id BETWEEN 1001 AND 1999;
DELETE FROM sentences WHERE id BETWEEN 1001 AND 1999;
DELETE FROM task_assets WHERE id BETWEEN 1001 AND 1999;
DELETE FROM speaker_mappings WHERE id BETWEEN 1001 AND 1999;
DELETE FROM quota_ledger WHERE id BETWEEN 1001 AND 1999;
DELETE FROM daily_quota WHERE user_id BETWEEN 1001 AND 1999;
DELETE FROM export_jobs WHERE id BETWEEN 1001 AND 1999;
DELETE FROM deletion_jobs WHERE id BETWEEN 1001 AND 1999;
DELETE FROM audit_log WHERE id BETWEEN 1001 AND 1999;
DELETE FROM recognition_tasks WHERE id BETWEEN 1001 AND 1999 AND idempotency_key LIKE 'seed-demo-task-%';
DELETE FROM upload_sessions WHERE id BETWEEN 1001 AND 1999 AND object_key LIKE 'seed-demo/%';
DELETE FROM users WHERE id BETWEEN 1001 AND 1999 AND wechat_openid LIKE 'seed_demo_openid_%';
DELETE FROM admin_users WHERE id BETWEEN 1001 AND 1999;
DELETE FROM audit_rule_versions WHERE id BETWEEN 1001 AND 1999;
DELETE FROM audit_rules WHERE id BETWEEN 1001 AND 1999 AND code LIKE 'DEMO-R%';
DELETE FROM rule_sets WHERE version = 19;
COMMIT;
SELECT '回滚完成' AS 结果;
