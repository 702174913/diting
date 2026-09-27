-- V1 基线内容校验和（只读）
-- 只覆盖 V1 已存在的列，因此同一份 SQL 可用于「迁移前 vs 迁移后」逐字节比对，
-- 用「内容指纹」证明 S01 没有改写历史业务数据。
-- 用法：mysql -h<host> -u<user> -D<db> --batch --skip-column-names < v1_content_checksum.sql

SET SESSION group_concat_max_len = 1048576;

SELECT 'users' AS tbl,
       IFNULL(MD5(GROUP_CONCAT(row_md5 ORDER BY id SEPARATOR '')), '<empty>') AS content_md5
FROM (SELECT id,
             MD5(CONCAT_WS('|', id,
                           IFNULL(username, '~'),
                           IFNULL(password_hash, '~'),
                           IFNULL(wechat_openid, '~'),
                           IFNULL(unionid, '~'),
                           nickname,
                           DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f'))) AS row_md5
      FROM users) u;

SELECT 'recognition_tasks' AS tbl,
       IFNULL(MD5(GROUP_CONCAT(row_md5 ORDER BY id SEPARATOR '')), '<empty>') AS content_md5
FROM (SELECT id,
             MD5(CONCAT_WS('|', id,
                           user_id,
                           IFNULL(task_id, '~'),
                           oss_object,
                           status,
                           IFNULL(duration_sec, '~'),
                           speaker_mapping_confirmed,
                           IFNULL(error_msg, '~'),
                           DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f'),
                           IFNULL(DATE_FORMAT(finished_at, '%Y-%m-%dT%H:%i:%s.%f'), '~'))) AS row_md5
      FROM recognition_tasks) t;

SELECT 'sentences' AS tbl,
       IFNULL(MD5(GROUP_CONCAT(row_md5 ORDER BY id SEPARATOR '')), '<empty>') AS content_md5
FROM (SELECT id,
             MD5(CONCAT_WS('|', id,
                           task_id,
                           IFNULL(speaker_id, '~'),
                           start_ms,
                           end_ms,
                           MD5(text),
                           IFNULL(speech_speed, '~'),
                           flagged,
                           IFNULL(rule_ref, '~'))) AS row_md5
      FROM sentences) s;
