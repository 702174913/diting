-- 独立计数结果下载/解析失败，避免正常轮询消耗故障重试额度。
ALTER TABLE recognition_tasks ADD COLUMN parse_failures INT NOT NULL DEFAULT 0;
