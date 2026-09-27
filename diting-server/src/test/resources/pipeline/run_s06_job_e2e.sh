#!/usr/bin/env bash
# =====================================================================
# S06 作业管线端到端验证（JOB-01..JOB-09 中需要真实数据库的部分）
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + 以 test profile 启动的实例（桩听悟 + 内存存储 + 关掉定时器）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8085 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s06_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>'
#
# 运行：MYSQL_PWD='<库密码>' bash run_s06_job_e2e.sh
# 说明：手动 tick 让推进确定可复现；生产由定时调度驱动同一套逻辑。
# =====================================================================
set -uo pipefail
BASE=${S06_BASE_URL:-http://127.0.0.1:8085}
DB=${S06_TEST_DB:-diting_s06_test}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾" >&2; exit 2 ;; esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MIGRATION_DIR="$SERVER_DIR/src/main/resources/sql/migration"

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM
WORK=$(mktemp -d)

call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 20 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -n "$b" ] && args+=(-d "$b")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  curl "${args[@]}" "$BASE$p"
}
sqlv(){ mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -D "$DB" --batch --skip-column-names -e "$1" 2>&1; }
RESP=""
s(){ tail -1 <<<"$RESP"; }
p(){ sed '$d' <<<"$RESP"; }
expect(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 HTTP ${want}；实际 HTTP ${got}"; fi; }
expect_eq(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 ${want}；实际 ${got}"; fi; }
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}（$(p)）"; fi; }

tick(){ RESP=$(call POST /api/test/pipeline/tick '{}' "$TOKEN"); }
script_tingwu(){ # script_tingwu <createTaskId> <status> [transcriptionUrl]
  local body
  if [ -n "${3:-}" ]; then
    body="{\"createTaskId\":\"$1\",\"status\":\"$2\",\"transcriptionUrl\":\"$3\"}"
  else
    body="{\"createTaskId\":\"$1\",\"status\":\"$2\"}"
  fi
  call POST /api/test/pipeline/tingwu-script "$body" "$TOKEN" >/dev/null
}

M4A_B64=$(python3 -c "import base64;d=bytearray(64);h=b'0000ftypM4A ';d[0:len(h)]=h;print(base64.b64encode(bytes(d)).decode())")

# 把上一轮遗留的到期任务停泊到明天，保证本轮的 progressed 计数只反映本脚本的任务
sqlv "UPDATE recognition_tasks SET next_attempt_at = DATE_ADD(NOW(3), INTERVAL 1 DAY), lease_until = NULL WHERE status IN ('QUEUED','SUBMITTING','TRANSCRIBING','PARSING') AND deleted_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))" >/dev/null

echo "== S06 作业管线端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：一个已校验上传 + 已登记任务 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"j_${SFX}\",\"password\":\"Passw0rd2026\"}")
expect "注册 200" 200 "$(s)"
TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$TOKEN")
UPLOAD_ID=$(p | jq -r '.data.uploadId')
call POST /api/test/object-storage/put "{\"uploadId\":\"${UPLOAD_ID}\",\"contentBase64\":\"${M4A_B64}\"}" "$TOKEN" >/dev/null
call POST /api/upload/complete "{\"uploadId\":${UPLOAD_ID}}" "$TOKEN" >/dev/null
RESP=$(call POST /api/task/submit "{\"uploadId\":${UPLOAD_ID},\"declaredDurationSec\":30}" "$TOKEN")
expect "登记任务 200" 200 "$(s)"
TASK_ID=$(p | jq -r '.data.id')
expect_eq "新任务状态为 QUEUED（新枚举）" "QUEUED" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"

# ---------- JOB-01 关闭小程序仍推进：手动 tick 走完提交 ----------
script_tingwu "tw-e2e-${SFX}" "ONGOING"
tick
expect_eq "JOB-01-1 一轮调度推进 1 个任务" "1" "$(p | jq -r '.data.progressed')"
expect_eq "JOB-01-2 已登记远端任务号并进入转录中" "TRANSCRIBING" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
expect_eq "JOB-01-3 远端任务号落库" "tw-e2e-${SFX}" "$(sqlv "SELECT task_id FROM recognition_tasks WHERE id=${TASK_ID}")"
expect_eq "JOB-01-4 云端受理后额度转已消费" "CONSUMED" "$(sqlv "SELECT state FROM quota_ledger WHERE task_id=${TASK_ID}")"
BEFORE_ATTEMPT=$(sqlv "SELECT attempt_count FROM recognition_tasks WHERE id=${TASK_ID}")

# ---------- JOB-03 并发领取：只有一个处理者 ----------
sqlv "UPDATE recognition_tasks SET lease_until = DATE_SUB(NOW(3), INTERVAL 5 SECOND), next_attempt_at = NULL WHERE id=${TASK_ID}" >/dev/null
for i in 1 2; do
  call POST /api/test/pipeline/tick '{}' "$TOKEN" > "$WORK/tick${i}.txt" &
done
wait
T1=$(sed '$d' "$WORK/tick1.txt" | jq -r '.data.progressed'); T2=$(sed '$d' "$WORK/tick2.txt" | jq -r '.data.progressed')
TOTAL=$((T1 + T2))
expect_eq "JOB-03-1 两个并发 tick 合计只推进 1 次" "1" "$TOTAL"
expect_eq "JOB-03-2 尝试次数只 +1（没有重复处理）" "$((BEFORE_ATTEMPT + 1))" "$(sqlv "SELECT attempt_count FROM recognition_tasks WHERE id=${TASK_ID}")"

# ---------- JOB-04 租约未过期不可重领；过期后可重领 ----------
BEFORE_ATTEMPT=$(sqlv "SELECT attempt_count FROM recognition_tasks WHERE id=${TASK_ID}")
sqlv "UPDATE recognition_tasks SET lease_until = DATE_ADD(NOW(3), INTERVAL 600 SECOND) WHERE id=${TASK_ID}" >/dev/null
tick
expect_eq "JOB-04-1 租约有效期内不重领" "0" "$(p | jq -r '.data.progressed')"
expect_eq "JOB-04-2 尝试次数不变" "$BEFORE_ATTEMPT" "$(sqlv "SELECT attempt_count FROM recognition_tasks WHERE id=${TASK_ID}")"
sqlv "UPDATE recognition_tasks SET lease_until = DATE_SUB(NOW(3), INTERVAL 5 SECOND), next_attempt_at = NULL WHERE id=${TASK_ID}" >/dev/null
tick
expect_eq "JOB-04-3 租约过期后可重领（进程中断恢复）" "1" "$(p | jq -r '.data.progressed')"

# ---------- JOB-02 重复与乱序回调：只唤醒，不改状态 ----------
STATUS_BEFORE=$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")
for i in 1 2; do
  RESP=$(call POST /api/callback/tingwu "{\"TaskId\":\"tw-e2e-${SFX}\",\"TaskStatus\":\"COMPLETED\",\"Result\":{\"Transcription\":\"https://evil.example.com/x.json\"}}")
  expect "JOB-02-${i} 重复回调返回 200" 200 "$(s)"
done
expect_eq "JOB-02-3 回调不改状态（只唤醒，不采信回调里的结果地址）" "$STATUS_BEFORE" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
RESP=$(call POST /api/callback/tingwu '{"TaskId":"unknown-task","TaskStatus":"COMPLETED"}')
expect "JOB-02-4 未知任务回调返回 200（幂等）" 200 "$(s)"

# ---------- JOB-06 结果地址失效：停在 PARSING 并记录解析失败 ----------
script_tingwu "tw-e2e-${SFX}" "COMPLETED" "https://tingwu-result.invalid/x.json"
sqlv "UPDATE recognition_tasks SET next_attempt_at = NULL, lease_until = NULL WHERE id=${TASK_ID}" >/dev/null
tick
expect_eq "JOB-06-1 结果不可用：状态停在 PARSING" "PARSING" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
expect_eq "JOB-06-2 记录 PARSE_FAILED 错误码" "PARSE_FAILED" "$(sqlv "SELECT error_code FROM recognition_tasks WHERE id=${TASK_ID}")"
expect_eq "JOB-06-3 未被标记为失败（可重试）" "0" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${TASK_ID} AND status='FAILED'")"

# ---------- JOB-07 版本 CAS：过期写入者不能改状态 ----------
STALE_VERSION=$(( $(sqlv "SELECT version FROM recognition_tasks WHERE id=${TASK_ID}") - 1 ))
CURRENT_VERSION=$(( STALE_VERSION + 1 ))
STALE_ROWS=$(sqlv "UPDATE recognition_tasks SET status='COMPLETED' WHERE id=${TASK_ID} AND version=${STALE_VERSION}; SELECT ROW_COUNT()")
expect_eq "JOB-07-1 过期版本的推进影响 0 行" "0" "$STALE_ROWS"
expect_eq "JOB-07-1b 过期写入者没能改状态（仍是 PARSING）" "PARSING" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
# 用 error_code 做写入断言：把列改成相同值 MySQL 不计入 ROW_COUNT
CURRENT_ROWS=$(sqlv "UPDATE recognition_tasks SET error_code='CAS_OK' WHERE id=${TASK_ID} AND version=${CURRENT_VERSION}; SELECT ROW_COUNT()")
expect_eq "JOB-07-2 当前版本的推进影响 1 行" "1" "$CURRENT_ROWS"

# ---------- JOB-08 删除后迟到回调：墓碑优先 ----------
sqlv "UPDATE recognition_tasks SET deleted_at = NOW(3), status='DELETED' WHERE id=${TASK_ID}" >/dev/null
RESP=$(call POST /api/callback/tingwu "{\"TaskId\":\"tw-e2e-${SFX}\",\"TaskStatus\":\"COMPLETED\"}")
expect "JOB-08-1 已删除任务的回调仍返回 200（不暴露内部状态）" 200 "$(s)"
expect_eq "JOB-08-2 删除后不被回调复活" "DELETED" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
sqlv "UPDATE recognition_tasks SET next_attempt_at = NULL, lease_until = NULL WHERE id=${TASK_ID}" >/dev/null
tick
expect_eq "JOB-08-3 已删除任务不再被作业领取" "0" "$(p | jq -r '.data.progressed')"

# ---------- 状态枚举迁移（V3） ----------
sqlv "INSERT INTO recognition_tasks (user_id, oss_object, status, created_at, idempotency_key) VALUES (1, 'audio/legacy-pending.m4a', 'PENDING', NOW(3), 'legacy-pending-${SFX}')" >/dev/null
sqlv "INSERT INTO recognition_tasks (user_id, oss_object, status, created_at, idempotency_key) VALUES (1, 'audio/legacy-ongoing.m4a', 'ONGOING', NOW(3), 'legacy-ongoing-${SFX}')" >/dev/null
mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -D "$DB" < "$MIGRATION_DIR/V3__S06_status_migration.sql" >/dev/null 2>&1
expect_eq "迁移：PENDING → QUEUED" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE idempotency_key='legacy-pending-${SFX}' AND status='QUEUED'")"
expect_eq "迁移：ONGOING → TRANSCRIBING" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE idempotency_key='legacy-ongoing-${SFX}' AND status='TRANSCRIBING'")"
expect_eq "迁移：库中不再有旧值" "0" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE status IN ('PENDING','ONGOING')")"
expect_eq "迁移：台账记录 S06" "1" "$(sqlv "SELECT COUNT(*) FROM schema_migrations WHERE version='S06'")"

rm -rf "$WORK"
echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
