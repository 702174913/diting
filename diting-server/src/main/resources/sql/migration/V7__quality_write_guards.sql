-- 部署前人工执行并验证；本次代码修复没有连接/修改业务库。
-- 检查下列结果必须为空；存在历史共享上传时先核实归属/清理策略，不自动删数据。
SELECT upload_id, COUNT(*) AS references_count
FROM task_assets WHERE upload_id IS NOT NULL GROUP BY upload_id HAVING COUNT(*) > 1;

-- 唯一约束是消费 CAS 外的数据库兜底；存在重复数据时故意失败，不能忽略继续上线。
ALTER TABLE task_assets ADD UNIQUE KEY uk_asset_upload (upload_id);

CREATE TABLE IF NOT EXISTS mutation_locks (
  name VARCHAR(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO mutation_locks (name) VALUES ('ADMIN_MEMBERSHIP')
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO schema_migrations (version, name)
VALUES ('V7', '上传引用唯一与管理员成员变更公共锁');
