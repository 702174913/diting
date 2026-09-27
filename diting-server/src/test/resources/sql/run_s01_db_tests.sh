#!/usr/bin/env bash
# =====================================================================
# 谛听 · S01 限定迁移测试（DB-01..DB-06）
# 只在独立测试库上执行；脚本本身不保存任何凭据。
#
# 用法：
#   MYSQL_PWD='<密码>' bash run_s01_db_tests.sh
# 可选环境变量：
#   MYSQL_HOST(默认 127.0.0.1) MYSQL_PORT(3306) MYSQL_USER(root)
#   S01_TEST_DB(默认 diting_s01_test，必须以 _test 结尾)
#   S01_KEEP_TEST_DB=1 保留测试库（默认测试结束后删除）
#
# 对应开发计划 §4 的测试项：
#   DB-01 空库建表      DB-02 样本迁移内容不丢   DB-03 重复迁移保护
#   DB-04 约束有效性    DB-05 回滚演练恢复       DB-06 索引覆盖
# =====================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
SCHEMA="$SERVER_DIR/src/main/resources/sql/schema.sql"
V2="$SERVER_DIR/src/main/resources/sql/migration/V2__S01_schema_foundation.sql"
ROLLBACK="$SERVER_DIR/src/main/resources/sql/migration/V2__S01_rollback.sql"
SEED="$SCRIPT_DIR/seed_pre_migration.sql"
CHECKSUM="$SCRIPT_DIR/v1_content_checksum.sql"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
TEST_DB="${S01_TEST_DB:-diting_s01_test}"
KEEP_DB="${S01_KEEP_TEST_DB:-0}"

# --- 安全护栏：绝不针对非测试库执行 ---
case "$TEST_DB" in
    *_test) ;;
    *) echo "拒绝执行：测试库名必须以 _test 结尾（当前：$TEST_DB）" >&2; exit 2 ;;
esac
if [ -z "${MYSQL_PWD:-}" ]; then
    echo "缺少 MYSQL_PWD：请通过环境变量提供数据库密码，脚本不保存任何凭据。" >&2
    exit 2
fi

MYSQL=(mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" --connect-timeout=5 --batch --raw --skip-column-names)
PASS=0
FAIL=0

ok()  { printf 'PASS  %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL + 1)); }
check_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "期望=[$2] 实际=[$3]"; fi; }
check_nonempty() { if [ -n "$2" ] && [ "$2" != "<none>" ]; then ok "$1"; else bad "$1" "实际=[$2]"; fi; }

q() { # q <db> <sql> -> 单值输出
    "${MYSQL[@]}" -D "$1" -e "$2" 2>&1
}
expect_fail() { # expect_fail <用例名> <db> <sql>：该语句必须报错
    local name="$1" db="$2" sql="$3" out
    if out="$(q "$db" "$sql")"; then
        bad "$name" "预期报错但执行成功：$out"
    else
        ok "$name"
    fi
}
expect_ok() { # expect_ok <用例名> <db> <sql>
    local name="$1" db="$2" sql="$3" out
    if out="$(q "$db" "$sql")"; then
        ok "$name"
    else
        bad "$name" "预期成功但报错：$out"
    fi
}

recreate_db() {
    q mysql "DROP DATABASE IF EXISTS \`$TEST_DB\`"
    q mysql "CREATE DATABASE \`$TEST_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
}
# schema.sql 自带 CREATE DATABASE/USE，加载到测试库前必须先剥掉，否则会切到生产库
load_v1() { sed -e '/^CREATE DATABASE/d' -e '/^USE /d' "$SCHEMA" | "${MYSQL[@]}" -D "$TEST_DB" || return 1; }
load_sql() { "${MYSQL[@]}" -D "$TEST_DB" < "$1"; }
counts() { q "$TEST_DB" "SELECT CONCAT_WS(',', (SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM recognition_tasks), (SELECT COUNT(*) FROM sentences))"; }
checksums() { "${MYSQL[@]}" -D "$TEST_DB" < "$CHECKSUM" | paste -sd, -; }
table_list() { q information_schema "SELECT IFNULL(GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ','),'') FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TEST_DB' AND TABLE_TYPE='BASE TABLE'"; }
table_count() { q information_schema "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TEST_DB' AND TABLE_TYPE='BASE TABLE'"; }
idx_cols() { q "$TEST_DB" "SELECT IFNULL(GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX),'') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$1' AND INDEX_NAME='$2'"; }
idx_exists() { q "$TEST_DB" "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$1' AND INDEX_NAME='$2'"; }
col_meta() { q "$TEST_DB" "SELECT CONCAT_WS('|', COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT,'<null>')) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$1' AND COLUMN_NAME='$2'"; }

fp_sql() { # 结构指纹：列 / 索引 / CHECK 约束
    cat <<SQL
SELECT CONCAT_WS(' ~ ',
  IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                                           IFNULL(COLUMN_DEFAULT, '<null>'), EXTRA, COLUMN_KEY)
                                   ORDER BY COLUMN_NAME SEPARATOR ';'))
          FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$1'), '<none>'),
  IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME)
                                   ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ';'))
          FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$1'), '<none>'),
  IFNULL((SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE)
                                   ORDER BY tc.CONSTRAINT_NAME SEPARATOR ';'))
          FROM information_schema.TABLE_CONSTRAINTS tc
                   JOIN information_schema.CHECK_CONSTRAINTS cc
                        ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                            AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
          WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = '$1'
            AND tc.CONSTRAINT_TYPE = 'CHECK'), '<none>'));
SQL
}
fp3() { for t in users recognition_tasks sentences; do q "$TEST_DB" "$(fp_sql "$t")" | tr '\n' ' '; echo; done | paste -sd';' -; }

NEW_TABLES="upload_sessions speaker_mappings deletion_jobs audit_rules audit_rule_versions \
rule_sets report_versions violations app_config daily_quota quota_ledger admin_users audit_log export_jobs"
NEW_TASK_COLS="upload_id idempotency_key version attempt_count lease_until next_attempt_at error_code expires_at audio_purged deleted_at"

echo "== 谛听 S01 限定迁移测试 =="
echo "测试库：$TEST_DB @ $MYSQL_HOST:$MYSQL_PORT  用户：$MYSQL_USER"
echo "MySQL ：$(q mysql "SELECT VERSION()")"
echo

# ---------------------------------------------------------------------
echo "[DB-01] 空库建表"
recreate_db
load_v1 || bad "DB-01 加载 V1 基线" "schema.sql 加载失败"
before_tables=$(table_count)
check_eq "DB-01 V1 基线为 3 张表" "3" "$before_tables"
load_sql "$V2" >/dev/null || bad "DB-01 执行 V2 迁移" "迁移报错"
check_eq "DB-01 迁移后共 18 张表（3 旧 + 14 新 + schema_migrations）" "18" "$(table_count)"
missing=""
for t in $NEW_TABLES; do
    [ "$(q information_schema "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TEST_DB' AND TABLE_NAME='$t'")" = "1" ] || missing="$missing $t"
done
check_eq "DB-01 14 张新表全部建出" "" "$missing"
missing_cols=""
for c in $NEW_TASK_COLS; do
    [ "$(col_meta recognition_tasks "$c" | cut -d'|' -f1)" = "" ] && missing_cols="$missing_cols $c"
done
check_eq "DB-01 recognition_tasks 10 个扩展列全部存在" "" "$missing_cols"
check_eq "DB-01 sentences.sequence 为 int NOT NULL" "int|NO|<null>" "$(col_meta sentences sequence)"
check_eq "DB-01 台账版本唯一" "1" "$(q "$TEST_DB" "SELECT COUNT(*) FROM schema_migrations WHERE version='S01'")"
check_eq "DB-01 新表排序规则与既有表一致" "utf8mb4_0900_ai_ci" "$(q "$TEST_DB" "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='report_versions'")"
echo

# ---------------------------------------------------------------------
echo "[DB-02] 含样本迁移后内容不丢"
recreate_db
load_v1 >/dev/null
load_sql "$SEED" >/dev/null
pre_counts=$(counts)
pre_ck=$(checksums)
load_sql "$V2" >/dev/null
check_eq "DB-02 行数不变（users,recognition_tasks,sentences）" "$pre_counts" "$(counts)"
check_eq "DB-02 V1 列内容逐字节不变" "$pre_ck" "$(checksums)"
check_eq "DB-02 旧状态值未被改写" "COMPLETED,FAILED,ONGOING,PENDING" "$(q "$TEST_DB" "SELECT GROUP_CONCAT(DISTINCT status ORDER BY status) FROM recognition_tasks")"
check_eq "DB-02 句序按(start_ms,id)确定性回填" "1:1,2:2,3:1,4:2,5:3,6:1" \
    "$(q "$TEST_DB" "SELECT GROUP_CONCAT(CONCAT(id,':',sequence) ORDER BY id) FROM sentences")"
check_eq "DB-02 无空句序" "0" "$(q "$TEST_DB" "SELECT COUNT(*) FROM sentences WHERE sequence IS NULL")"
check_eq "DB-02 时间列精度对齐 DATETIME(3)" "3" \
    "$(q "$TEST_DB" "SELECT MAX(DATETIME_PRECISION) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recognition_tasks' AND COLUMN_NAME='created_at'")"
echo

# ---------------------------------------------------------------------
echo "[DB-03] 重复迁移保护"
pre_fp=$(fp3)
pre_tables=$(table_list)
load_sql "$V2" >/dev/null
rc=$?
check_eq "DB-03 二次执行迁移成功返回" "0" "$rc"
check_eq "DB-03 结构指纹完全一致" "$pre_fp" "$(fp3)"
check_eq "DB-03 表清单一致" "$pre_tables" "$(table_list)"
check_eq "DB-03 台账仍只有 1 行" "1" "$(q "$TEST_DB" "SELECT COUNT(*) FROM schema_migrations WHERE version='S01'")"
echo

# ---------------------------------------------------------------------
echo "[DB-04] 约束有效性（负例必须失败，正例必须成功）"
DBX="$TEST_DB"
expect_ok "DB-04 正例：合法句序可插入" "$DBX" \
    "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text) VALUES (4, 1, 0, 0, 500, '正例')"
expect_fail "DB-04 重复(task_id,sequence)被拒" "$DBX" \
    "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text) VALUES (1, 1, 0, 0, 500, '重复句序')"
expect_fail "DB-04 start_ms>end_ms 被拒" "$DBX" \
    "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text) VALUES (4, 2, 0, 900, 100, '时间倒挂')"

expect_ok "DB-04 正例：带幂等键的任务可插入" "$DBX" \
    "INSERT INTO recognition_tasks (user_id, idempotency_key, upload_id, status, created_at) VALUES (1, 'idem-1', 9001, 'QUEUED', NOW(3))"
expect_fail "DB-04 重复远端task_id被拒" "$DBX" \
    "INSERT INTO recognition_tasks (user_id, task_id, status, created_at) VALUES (1, 'tw-fixture-ongoing', 'QUEUED', NOW(3))"
expect_fail "DB-04 重复(user_id,idempotency_key)被拒" "$DBX" \
    "INSERT INTO recognition_tasks (user_id, idempotency_key, status, created_at) VALUES (1, 'idem-1', 'QUEUED', NOW(3))"
expect_fail "DB-04 重复upload_id被拒" "$DBX" \
    "INSERT INTO recognition_tasks (user_id, upload_id, status, created_at) VALUES (1, 9001, 'QUEUED', NOW(3))"

expect_ok "DB-04 正例：上传会话可插入" "$DBX" \
    "INSERT INTO upload_sessions (user_id, object_key, filename, consent_version, consented_at, signed_until, cleanup_after, created_at) VALUES (1, 'audio/u1/a.m4a', 'a.m4a', 'v1', NOW(3), NOW(3), NOW(3), NOW(3))"
expect_fail "DB-04 重复object_key被拒" "$DBX" \
    "INSERT INTO upload_sessions (user_id, object_key, filename, consent_version, consented_at, signed_until, cleanup_after, created_at) VALUES (1, 'audio/u1/a.m4a', 'a.m4a', 'v1', NOW(3), NOW(3), NOW(3), NOW(3))"

expect_ok "DB-04 正例：规则版本可插入" "$DBX" \
    "INSERT INTO audit_rule_versions (rule_id, version, group_name, name, description, match_type, role_scope, trigger_role, keywords, expected_keywords, window_size, deduct, created_at) VALUES (1, 1, 'g', 'n', 'd', 'hit', 'AGENT', NULL, JSON_ARRAY('a'), JSON_ARRAY(), NULL, 20, NOW(3))"
expect_fail "DB-04 扣分 101 被拒" "$DBX" \
    "INSERT INTO audit_rule_versions (rule_id, version, group_name, name, description, match_type, role_scope, trigger_role, keywords, expected_keywords, window_size, deduct, created_at) VALUES (1, 2, 'g', 'n', 'd', 'hit', 'AGENT', NULL, JSON_ARRAY('a'), JSON_ARRAY(), NULL, 101, NOW(3))"
expect_fail "DB-04 重复(rule_id,version)被拒" "$DBX" \
    "INSERT INTO audit_rule_versions (rule_id, version, group_name, name, description, match_type, role_scope, trigger_role, keywords, expected_keywords, window_size, deduct, created_at) VALUES (1, 1, 'g', 'n', 'd', 'hit', 'AGENT', NULL, JSON_ARRAY('a'), JSON_ARRAY(), NULL, 10, NOW(3))"

expect_ok "DB-04 正例：报告版本可插入" "$DBX" \
    "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, risk_score, compliance_score, threshold_snapshot, config_version, status, created_at) VALUES (1, 1, 1, 1, 60, 40, 60, 1, 'PUBLISHED', NOW(3))"
expect_fail "DB-04 风险分 101 被拒" "$DBX" \
    "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, risk_score, compliance_score, threshold_snapshot, config_version, status, created_at) VALUES (1, 2, 1, 1, 101, 0, 60, 1, 'PUBLISHED', NOW(3))"
expect_fail "DB-04 阈值 101 被拒" "$DBX" \
    "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, risk_score, compliance_score, threshold_snapshot, config_version, status, created_at) VALUES (1, 3, 1, 1, 10, 90, 101, 1, 'PUBLISHED', NOW(3))"
expect_fail "DB-04 重复(task_id,version)被拒" "$DBX" \
    "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, risk_score, compliance_score, threshold_snapshot, config_version, status, created_at) VALUES (1, 1, 1, 1, 10, 90, 60, 1, 'PUBLISHED', NOW(3))"

expect_ok "DB-04 正例：额度流水可插入" "$DBX" \
    "INSERT INTO quota_ledger (user_id, task_id, business_date, state, created_at, updated_at) VALUES (1, 1, '2026-09-24', 'RESERVED', NOW(3), NOW(3))"
expect_fail "DB-04 同一任务重复额度流水被拒" "$DBX" \
    "INSERT INTO quota_ledger (user_id, task_id, business_date, state, created_at, updated_at) VALUES (1, 1, '2026-09-24', 'RELEASED', NOW(3), NOW(3))"
expect_ok "DB-04 正例：额度计数可插入" "$DBX" \
    "INSERT INTO daily_quota (user_id, business_date) VALUES (1, '2026-09-24')"
expect_fail "DB-04 重复(user_id,business_date)被拒" "$DBX" \
    "INSERT INTO daily_quota (user_id, business_date) VALUES (1, '2026-09-24')"
expect_fail "DB-04 负额度被拒" "$DBX" \
    "INSERT INTO daily_quota (user_id, business_date, reserved) VALUES (2, '2026-09-24', -1)"
expect_fail "DB-04 负数已消费额度被拒" "$DBX" \
    "INSERT INTO daily_quota (user_id, business_date, consumed) VALUES (3, '2026-09-24', -5)"

expect_ok "DB-04 正例：命中证据可插入" "$DBX" \
    "INSERT INTO violations (report_version_id, rule_version_id, sentence_id, evidence_sentence_ids, ranges, deduct, note, evidence_key) VALUES (1, 1, 1, JSON_ARRAY(1), JSON_ARRAY(JSON_OBJECT('sentenceId', 1, 'start', 0, 'end', 2)), 20, 'n', 'k1')"
expect_fail "DB-04 重复证据键被拒" "$DBX" \
    "INSERT INTO violations (report_version_id, rule_version_id, sentence_id, evidence_sentence_ids, ranges, deduct, note, evidence_key) VALUES (1, 1, 1, JSON_ARRAY(1), JSON_ARRAY(), 20, 'n', 'k1')"

expect_ok "DB-04 正例：策略配置可插入" "$DBX" \
    "INSERT INTO app_config (config_key, config_value, version, updated_at) VALUES ('daily_free_quota', '3', 1, NOW(3))"
expect_fail "DB-04 重复配置键被拒" "$DBX" \
    "INSERT INTO app_config (config_key, config_value, version, updated_at) VALUES ('daily_free_quota', '5', 2, NOW(3))"

expect_ok "DB-04 正例：后台账号可插入" "$DBX" \
    "INSERT INTO admin_users (username, password_hash, nickname, role, created_at, updated_at) VALUES ('ops_fixture', 'hash', '运维', 'OPERATOR', NOW(3), NOW(3))"
expect_fail "DB-04 重复后台账号被拒" "$DBX" \
    "INSERT INTO admin_users (username, password_hash, nickname, role, created_at, updated_at) VALUES ('ops_fixture', 'hash', '运维', 'ADMIN', NOW(3), NOW(3))"

expect_ok "DB-04 正例：规则主表可插入" "$DBX" \
    "INSERT INTO audit_rules (code, current_version, enabled, created_at, updated_at) VALUES ('R-001', 1, 1, NOW(3), NOW(3))"
expect_fail "DB-04 重复规则编码被拒" "$DBX" \
    "INSERT INTO audit_rules (code, current_version, enabled, created_at, updated_at) VALUES ('R-001', 1, 1, NOW(3), NOW(3))"

expect_ok "DB-04 正例：角色映射可插入" "$DBX" \
    "INSERT INTO speaker_mappings (task_id, speaker_id, role, revision) VALUES (2, 0, 'AGENT', 1)"
expect_fail "DB-04 重复(task_id,revision,speaker_id)被拒" "$DBX" \
    "INSERT INTO speaker_mappings (task_id, speaker_id, role, revision) VALUES (2, 0, 'CUSTOMER', 1)"

expect_ok "DB-04 正例：删除作业可插入" "$DBX" \
    "INSERT INTO deletion_jobs (user_id, target_type, target_id, status, created_at) VALUES (1, 'TASK', 1, 'QUEUED', NOW(3))"
expect_fail "DB-04 重复删除目标被拒" "$DBX" \
    "INSERT INTO deletion_jobs (user_id, target_type, target_id, status, created_at) VALUES (1, 'TASK', 1, 'QUEUED', NOW(3))"
echo

# ---------------------------------------------------------------------
echo "[DB-05] 回滚演练恢复"
recreate_db
load_v1 >/dev/null
load_sql "$SEED" >/dev/null
v1_fp=$(fp3)
v1_ck=$(checksums)
load_sql "$V2" >/dev/null
load_sql "$ROLLBACK" >/dev/null
check_eq "DB-05 回滚后结构指纹与迁移前一致" "$v1_fp" "$(fp3)"
check_eq "DB-05 回滚后内容不变" "$v1_ck" "$(checksums)"
check_eq "DB-05 回滚后只剩 V1 三张表" "3" "$(table_count)"
check_eq "DB-05 回滚后 V1 索引恢复（idx_user）" "1" "$(idx_exists recognition_tasks idx_user)"
check_eq "DB-05 回滚后 V1 索引恢复（idx_task）" "1" "$(idx_exists sentences idx_task)"
check_eq "DB-05 回滚后 status 宽度回到 VARCHAR(16)" "varchar(16)|NO|PENDING" "$(col_meta recognition_tasks status)"
check_eq "DB-05 回滚后 oss_object 恢复 NOT NULL" "varchar(255)|NO|<null>" "$(col_meta recognition_tasks oss_object)"

# 护栏：新表已有数据时必须拒绝回滚
load_sql "$V2" >/dev/null
q "$TEST_DB" "INSERT INTO app_config (config_key, config_value, version, updated_at) VALUES ('x', '1', 1, NOW(3))" >/dev/null
if "${MYSQL[@]}" -D "$TEST_DB" < "$ROLLBACK" >/dev/null 2>&1; then
    bad "DB-05 护栏：新表有数据时回滚被拒" "回滚未报错"
else
    ok "DB-05 护栏：新表有数据时回滚被拒"
fi
check_eq "DB-05 被拒后数据仍在" "1" "$(q "$TEST_DB" "SELECT COUNT(*) FROM app_config")"
q "$TEST_DB" "DELETE FROM app_config" >/dev/null
load_sql "$ROLLBACK" >/dev/null
check_eq "DB-05 清空数据后可正常回滚" "3" "$(table_count)"
echo

# ---------------------------------------------------------------------
echo "[DB-06] 索引覆盖迁移前分页与队列领取查询"
recreate_db
load_v1 >/dev/null
load_sql "$SEED" >/dev/null
load_sql "$V2" >/dev/null
check_eq "DB-06 idx_user_created=(user_id,created_at,id)" "user_id,created_at,id" "$(idx_cols recognition_tasks idx_user_created)"
check_eq "DB-06 idx_queue=(status,next_attempt_at,lease_until)" "status,next_attempt_at,lease_until" "$(idx_cols recognition_tasks idx_queue)"
check_eq "DB-06 idx_expiry=(expires_at,audio_purged)" "expires_at,audio_purged" "$(idx_cols recognition_tasks idx_expiry)"
check_eq "DB-06 uk_task_seq=(task_id,sequence)" "task_id,sequence" "$(idx_cols sentences uk_task_seq)"
check_eq "DB-06 冗余旧索引 idx_user 已移除" "0" "$(idx_exists recognition_tasks idx_user)"
check_eq "DB-06 冗余旧索引 idx_task(recognition_tasks) 已移除" "0" "$(idx_exists recognition_tasks idx_task)"

# 造 300 行数据让优化器有真实选择空间（只有少数行可领取，贴近真实队列分布）
q "$TEST_DB" "INSERT INTO recognition_tasks (user_id, status, oss_object, created_at, next_attempt_at, lease_until)
              WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n + 1 FROM seq WHERE n < 300)
              SELECT 1 + (n % 3), 'QUEUED', CONCAT('audio/bulk/', n, '.m4a'), NOW(3),
                     CASE WHEN n % 50 = 0 THEN NULL ELSE DATE_ADD(NOW(3), INTERVAL n MINUTE) END,
                     CASE WHEN n % 7 = 0 THEN DATE_ADD(NOW(3), INTERVAL 30 MINUTE) ELSE NULL END
              FROM seq" >/dev/null
q "$TEST_DB" "ANALYZE TABLE recognition_tasks" >/dev/null

plan_user="$(q "$TEST_DB" "EXPLAIN SELECT id, status FROM recognition_tasks WHERE user_id = 1 ORDER BY created_at, id LIMIT 20")"
check_eq "DB-06 用户分页查询走 idx_user_created" "idx_user_created" "$(echo "$plan_user" | awk -F'\t' '{print $7}')"
if echo "$plan_user" | awk -F'\t' '{print $12}' | grep -q 'Using filesort'; then
    bad "DB-06 用户分页无需额外排序" "仍存在 Using filesort"
else
    ok "DB-06 用户分页无需额外排序"
fi

plan_queue="$(q "$TEST_DB" "EXPLAIN SELECT id FROM recognition_tasks WHERE status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3)) AND (lease_until IS NULL OR lease_until < NOW(3)) ORDER BY next_attempt_at LIMIT 5")"
check_eq "DB-06 队列领取查询走 idx_queue" "idx_queue" "$(echo "$plan_queue" | awk -F'\t' '{print $7}')"
if echo "$plan_queue" | awk -F'\t' '{print $5}' | grep -qE '^(ALL|index)$'; then
    bad "DB-06 队列领取不是全表扫描" "type=$(echo "$plan_queue" | awk -F'\t' '{print $5}')"
else
    ok "DB-06 队列领取不是全表扫描"
fi
echo

# ---------------------------------------------------------------------
if [ "$KEEP_DB" != "1" ]; then
    q mysql "DROP DATABASE IF EXISTS \`$TEST_DB\`"
    echo "已清理测试库 ${TEST_DB}（S01_KEEP_TEST_DB=1 可保留）"
fi
echo "== 结果：PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
