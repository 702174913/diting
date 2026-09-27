-- 迁移前样本数据（合成数据，不含任何真实用户信息）
-- 用途：DB-02/DB-05 —— 在 V1 结构上造出与线上同形状的历史数据（含 PENDING/ONGOING 旧状态、
--       无 sequence 列的句子、同 start_ms 需要按 id 兜底的排序场景），迁移后逐字节比对。
-- 用法：mysql -h<host> -u<user> -D<db> < seed_pre_migration.sql
-- 前置：已在该库执行过 V1 基线（schema.sql 的三张表）

-- 期望句序（迁移后应得到）：task1: 1→1, 2→2；task2: 3→1, 4→2, 5→3；task3: 6→1
INSERT INTO users (id, username, password_hash, wechat_openid, unionid, nickname, created_at) VALUES
    (1, 'test_account', '$2a$10$fixtureplaceholderplaceholderplaceholderplaceholderplaceholde', NULL, NULL, '测试账号', '2026-09-01 09:00:00'),
    (2, NULL, NULL, 'openid_fixture_0001', 'unionid_fixture_0001', '测试微信用户', '2026-09-02 10:30:00');

INSERT INTO recognition_tasks
    (id, user_id, task_id, oss_object, status, duration_sec, speaker_mapping_confirmed, error_msg, created_at, finished_at) VALUES
    (1, 1, NULL, 'audio/2026/09/fixture-pending.m4a', 'PENDING', NULL, 0, NULL, '2026-09-20 09:00:00', NULL),
    (2, 1, 'tw-fixture-ongoing', 'audio/2026/09/fixture-ongoing.mp3', 'ONGOING', 455, 0, NULL, '2026-09-21 09:15:00', NULL),
    (3, 2, 'tw-fixture-done', 'audio/2026/09/fixture-done.m4a', 'COMPLETED', 192, 1, NULL, '2026-09-22 14:00:00', '2026-09-22 14:03:00'),
    (4, 2, 'tw-fixture-failed', 'audio/2026/09/fixture-failed.wav', 'FAILED', 30, 0, '远端任务失败（fixture）', '2026-09-23 08:00:00', '2026-09-23 08:01:00');

INSERT INTO sentences (id, task_id, speaker_id, start_ms, end_ms, text, speech_speed, flagged, rule_ref) VALUES
    (1, 1, 0, 0,    1200, '你好，这里是测试语料第一句。', 240, 0, NULL),
    (2, 1, 1, 1200, 3000, '第二句用于验证时间顺序解析。', 230, 0, '测试规则'),
    (3, 2, 0, 0,    900,  '测试语料：第三句。', 250, 0, NULL),
    (4, 2, 1, 900,  2400, '相同起始时间按 id 兜底排序（先）。', 220, 1, '测试规则'),
    (5, 2, 0, 900,  1500, '相同起始时间按 id 兜底排序（后）。', 245, 0, NULL),
    (6, 3, 0, 0,    800,  '第四句：完成任务的语料。', 260, 0, NULL);
