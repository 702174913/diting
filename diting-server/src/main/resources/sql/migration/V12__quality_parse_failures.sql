-- 独立计数结果下载/解析失败，避免正常轮询消耗故障重试额度。
-- 幂等策略：**不幂等**——普通 ALTER，重复执行会报 Duplicate column。
-- 执行前先查 information_schema 与 schema_migrations：列已存在只是缺台账时，只补下面的 INSERT。
ALTER TABLE recognition_tasks ADD COLUMN parse_failures INT NOT NULL DEFAULT 0;

INSERT INTO schema_migrations (version, name)
VALUES ('V12', '结果下载/解析失败独立计数');
