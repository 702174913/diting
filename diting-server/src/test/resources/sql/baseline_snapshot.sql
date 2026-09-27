-- 迁移前基线快照（只读，不修改任何对象）
-- 用途：S01 迁移前记录结构、行数、内容校验和，作为「内容不丢」的比对基准。
-- 用法：mysql -h<host> -u<user> -D<db> --batch < baseline_snapshot.sql

SET SESSION group_concat_max_len = 1048576;

SELECT NOW(3)              AS snapshot_at,
       VERSION()           AS mysql_version,
       DATABASE()          AS db_name,
       @@collation_database AS db_collation,
       @@session.time_zone AS session_tz;

SELECT '--- SHOW CREATE TABLE users ---' AS section;
SHOW CREATE TABLE users;
SELECT '--- SHOW CREATE TABLE recognition_tasks ---' AS section;
SHOW CREATE TABLE recognition_tasks;
SELECT '--- SHOW CREATE TABLE sentences ---' AS section;
SHOW CREATE TABLE sentences;

SELECT '--- 行数 ---' AS section;
SELECT 'users' AS tbl, COUNT(*) AS rows_cnt FROM users
UNION ALL SELECT 'recognition_tasks', COUNT(*) FROM recognition_tasks
UNION ALL SELECT 'sentences', COUNT(*) FROM sentences;

SELECT '--- 旧状态取值分布（迁移不得改写） ---' AS section;
SELECT status, COUNT(*) AS cnt FROM recognition_tasks GROUP BY status ORDER BY status;

SELECT '--- 迁移前结构指纹（cols_fp / idx_fp / chk_fp） ---' AS section;
SELECT t.TABLE_NAME,
       IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                                                IFNULL(COLUMN_DEFAULT, '<null>'), EXTRA, COLUMN_KEY)
                                        ORDER BY COLUMN_NAME SEPARATOR ';'))
               FROM information_schema.COLUMNS c
               WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = t.TABLE_NAME), '<none>') AS cols_fp,
       IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME)
                                        ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ';'))
               FROM information_schema.STATISTICS s
               WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = t.TABLE_NAME), '<none>') AS idx_fp,
       IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE)
                                        ORDER BY tc.CONSTRAINT_NAME SEPARATOR ';'))
               FROM information_schema.TABLE_CONSTRAINTS tc
                        JOIN information_schema.CHECK_CONSTRAINTS cc
                             ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                                 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
               WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = t.TABLE_NAME
                 AND tc.CONSTRAINT_TYPE = 'CHECK'), '<none>') AS chk_fp
FROM information_schema.TABLES t
WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'
ORDER BY t.TABLE_NAME;
