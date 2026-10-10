-- 仅按可验证的演示标记及其关联键清理；不要仅凭 1001—1999 号段删除真实数据。
-- admin_users、rule_sets、审计日志和每日额度没有可靠的演示标记，不在自动回滚范围。
SET NAMES utf8mb4;
START TRANSACTION;
CREATE TEMPORARY TABLE diting_seed_tasks (id BIGINT PRIMARY KEY)
    SELECT id FROM recognition_tasks
    WHERE id BETWEEN 1001 AND 1999 AND idempotency_key LIKE 'seed-demo-task-%';
CREATE TEMPORARY TABLE diting_seed_reports (id BIGINT PRIMARY KEY)
    SELECT rv.id FROM report_versions rv JOIN diting_seed_tasks t ON t.id = rv.task_id;

DELETE v FROM violations v JOIN diting_seed_reports r ON r.id = v.report_version_id;
DELETE e FROM export_jobs e JOIN diting_seed_reports r ON r.id = e.report_version_id;
DELETE rv FROM report_versions rv JOIN diting_seed_reports r ON r.id = rv.id;
DELETE s FROM sentences s JOIN diting_seed_tasks t ON t.id = s.task_id;
DELETE a FROM task_assets a JOIN diting_seed_tasks t ON t.id = a.task_id;
DELETE m FROM speaker_mappings m JOIN diting_seed_tasks t ON t.id = m.task_id;
DELETE q FROM quota_ledger q JOIN diting_seed_tasks t ON t.id = q.task_id;
DELETE j FROM deletion_jobs j JOIN diting_seed_tasks t
    ON j.target_type = 'TASK' AND j.target_id = t.id;
DELETE t FROM recognition_tasks t JOIN diting_seed_tasks seed ON seed.id = t.id;
DELETE FROM upload_sessions WHERE id BETWEEN 1001 AND 1999 AND object_key LIKE 'seed-demo/%';
-- 用户及规则可能已被真实记录引用，保留以免误删或遗留悬空引用。
COMMIT;
DROP TEMPORARY TABLE diting_seed_tasks;
DROP TEMPORARY TABLE diting_seed_reports;
SELECT '演示任务及关联数据已清理；账号、规则、额度与审计记录需人工核对' AS result;
