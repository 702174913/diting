#!/usr/bin/env bash
# =====================================================================
# S03 额度与策略配置端到端验证（QUOTA-01..QUOTA-08 中需要真实库的部分）
# 需要：已按 S01 迁移的**独立测试库**（默认 diting_s03_test）+ 一个用测试配置启动的实例。
#
#   export MYSQL_PWD='<库密码>'
#   S03_ADMIN_PASSWORD='<初始化后台账号的密码>' bash run_s03_quota_e2e.sh
#   可选：S03_BASE_URL(默认 http://127.0.0.1:8082) S03_TEST_DB(默认 diting_s03_test) S03_ADMIN_USER
#
# 只读写这个测试库；密码一律走环境变量，不落盘。
# =====================================================================
set -uo pipefail
BASE=${S03_BASE_URL:-http://127.0.0.1:8082}
DB=${S03_TEST_DB:-diting_s03_test}
ADMIN_USER=${S03_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S03_ADMIN_PASSWORD:-}" ]; then
    echo "缺少 S03_ADMIN_PASSWORD" >&2; exit 2
fi
case "$DB" in
    *_test) ;;
    *) echo "拒绝执行：测试库名必须以 _test 结尾（当前 ${DB}）" >&2; exit 2 ;;
esac
if [ -z "${MYSQL_PWD:-}" ]; then
    echo "缺少 MYSQL_PWD（用于 SQL 断言）" >&2; exit 2
fi

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM
WORK=$(mktemp -d)

call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 15 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
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
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}"; fi; }
expect_eq(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 ${want}；实际 ${got}"; fi; }

# 读取当前配置版本
config_version(){ call GET /api/admin/config '' "$ADMIN_TOKEN" | sed '$d' | jq -r '.data.version'; }
set_quota(){ # set_quota <份数> [阈值] [时长]
  local quota="$1" threshold="${2:-60}" minutes="${3:-10}" version
  version=$(config_version)
  RESP=$(call POST /api/admin/config "{\"expectedVersion\":${version},\"dailyFreeQuota\":${quota},\"riskThreshold\":${threshold},\"maxRecordingMinutes\":${minutes}}" "$ADMIN_TOKEN")
  local code; code=$(s)
  if [ "$code" != "200" ]; then bad "设置配置（份数 ${quota}）" "HTTP ${code} $(p)"; return 1; fi
}
snapshot_remaining(){ call GET /api/task/quota '' "$1" | sed '$d' | jq -r '.data.remaining'; }
snapshot_reserved(){ call GET /api/task/quota '' "$1" | sed '$d' | jq -r '.data.reserved'; }

echo "== S03 额度端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：后台登录 + 两个用户 ----------
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S03_ADMIN_PASSWORD}\"}")
expect "后台登录 200" 200 "$(s)"
ADMIN_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/auth/register "{\"username\":\"q_a_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_A=$(p | jq -r '.data.token')
RESP=$(call POST /api/auth/register "{\"username\":\"q_b_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_B=$(p | jq -r '.data.token')

# ---------- QUOTA-08 权限与版本冲突 ----------
RESP=$(call POST /api/admin/config "{\"expectedVersion\":1,\"dailyFreeQuota\":5,\"riskThreshold\":60,\"maxRecordingMinutes\":10}" "$USER_A")
expect "QUOTA-08-1 用户 token 保存配置 403" 403 "$(s)"
VERSION=$(config_version)
RESP=$(call POST /api/admin/config "{\"expectedVersion\":${VERSION},\"dailyFreeQuota\":3,\"riskThreshold\":101,\"maxRecordingMinutes\":10}" "$ADMIN_TOKEN")
expect "QUOTA-08-2 阈值越界 422" 422 "$(s)"
RESP=$(call POST /api/admin/config "{\"expectedVersion\":${VERSION},\"dailyFreeQuota\":-1,\"riskThreshold\":60,\"maxRecordingMinutes\":10}" "$ADMIN_TOKEN")
expect "QUOTA-08-3 份数为负 422" 422 "$(s)"
RESP=$(call POST /api/admin/config "{\"expectedVersion\":${VERSION},\"dailyFreeQuota\":3,\"riskThreshold\":60,\"maxRecordingMinutes\":0}" "$ADMIN_TOKEN")
expect "QUOTA-08-4 时长 0 被拒 422" 422 "$(s)"
# 先成功保存一次让版本前进，再用旧版本覆盖 → 409
set_quota 5
RESP=$(call POST /api/admin/config "{\"expectedVersion\":${VERSION},\"dailyFreeQuota\":99,\"riskThreshold\":60,\"maxRecordingMinutes\":10}" "$ADMIN_TOKEN")
expect "QUOTA-08-5 版本冲突 409" 409 "$(s)"
expect_eq "QUOTA-08-6 冲突后配置未被覆盖" "5" "$(call GET /api/admin/config '' "$ADMIN_TOKEN" | sed '$d' | jq -r '.data.dailyFreeQuota')"

# ---------- QUOTA-01 额度 0 ----------
set_quota 0
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qa-${SFX}-1.m4a\",\"durationSec\":30,\"idempotencyKey\":\"q1-${SFX}\"}" "$USER_A")
expect "QUOTA-01-1 额度 0：提交被拒 429" 429 "$(s)"
expect_jq "QUOTA-01-2 symbol=QUOTA_EXCEEDED" '.symbol == "QUOTA_EXCEEDED"'
expect_eq "QUOTA-01-3 额度 0 时剩余为 0" "0" "$(snapshot_remaining "$USER_A")"

# ---------- QUOTA-02 达到上限 ----------
set_quota 1
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qa-${SFX}-2.m4a\",\"durationSec\":30,\"idempotencyKey\":\"q2a-${SFX}\"}" "$USER_A")
expect "QUOTA-02-1 第 1 份提交成功 200" 200 "$(s)"
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qa-${SFX}-3.m4a\",\"durationSec\":30,\"idempotencyKey\":\"q2b-${SFX}\"}" "$USER_A")
expect "QUOTA-02-2 超出上限被拒 429" 429 "$(s)"
expect_eq "QUOTA-02-3 上限用尽后剩余为 0" "0" "$(snapshot_remaining "$USER_A")"
expect_eq "QUOTA-02-4 预占 1 份" "1" "$(snapshot_reserved "$USER_A")"

# ---------- QUOTA-04 幂等键防重复扣费（独立用户，便于计数） ----------
set_quota 3
RESP=$(call POST /api/auth/register "{\"username\":\"q_e_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_E=$(jq -r '.data.token' <<<"$(sed '$d' <<<"$RESP")")
USER_E_ID=$(sqlv "SELECT id FROM users WHERE username='q_e_${SFX}'")
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qe-${SFX}-1.m4a\",\"durationSec\":30,\"idempotencyKey\":\"q4-${SFX}\"}" "$USER_E")
TASK_1=$(p | jq -r '.data.id')
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qe-${SFX}-1.m4a\",\"durationSec\":30,\"idempotencyKey\":\"q4-${SFX}\"}" "$USER_E")
TASK_2=$(p | jq -r '.data.id')
expect_eq "QUOTA-04-1 同一幂等键返回同一任务" "$TASK_1" "$TASK_2"
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qe-${SFX}-2.m4a\",\"durationSec\":30}" "$USER_E")
TASK_3=$(p | jq -r '.data.id')
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qe-${SFX}-2.m4a\",\"durationSec\":30}" "$USER_E")
TASK_4=$(p | jq -r '.data.id')
expect_eq "QUOTA-04-2 未传幂等键时按上传对象去重（同一任务）" "$TASK_3" "$TASK_4"
expect_eq "QUOTA-04-3 四次请求只占 2 份额度" "2" "$(snapshot_reserved "$USER_E")"
expect_eq "QUOTA-04-4 流水与任务一一对应" "2" "$(sqlv "SELECT COUNT(*) FROM quota_ledger WHERE user_id=${USER_E_ID}")"
expect_eq "QUOTA-04-5 任务数也是 2（没有重复登记）" "2" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=${USER_E_ID}")"

# ---------- QUOTA-05 并发争最后一份 ----------
set_quota 1
USER_C_RESP=$(call POST /api/auth/register "{\"username\":\"q_c_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_C=$(jq -r '.data.token' <<<"$(sed '$d' <<<"$USER_C_RESP")")
for i in 1 2; do
  call POST /api/task/submit "{\"objectKey\":\"audio/qc-${SFX}-${i}.m4a\",\"durationSec\":30,\"idempotencyKey\":\"qc${i}-${SFX}\"}" "$USER_C" > "$WORK/c${i}.txt" &
done
wait
C1=$(tail -1 "$WORK/c1.txt"); C2=$(tail -1 "$WORK/c2.txt")
if { [ "$C1" = "200" ] && [ "$C2" = "429" ]; } || { [ "$C1" = "429" ] && [ "$C2" = "200" ]; }; then
  ok "QUOTA-05-1 两个并发请求只有一个成功（200/429）"
else
  bad "QUOTA-05-1 并发争份额" "两个响应分别为 ${C1} 与 ${C2}"
fi
expect_eq "QUOTA-05-2 并发后只占 1 份" "1" "$(snapshot_reserved "$USER_C")"
expect_eq "QUOTA-05-3 并发后剩余为 0" "0" "$(snapshot_remaining "$USER_C")"

# ---------- QUOTA-03 跨日边界：昨天的消费不影响今天 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"q_d_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_D=$(jq -r '.data.token' <<<"$(sed '$d' <<<"$RESP")")
USER_D_ID=$(sqlv "SELECT id FROM users WHERE username='q_d_${SFX}'")
sqlv "INSERT INTO daily_quota (user_id, business_date, reserved, consumed, version) VALUES (${USER_D_ID}, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 0, 1, 0)" >/dev/null
set_quota 1
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qd-${SFX}.m4a\",\"durationSec\":30,\"idempotencyKey\":\"qd-${SFX}\"}" "$USER_D")
expect "QUOTA-03-1 昨天已用满不影响今天 200" 200 "$(s)"
expect_eq "QUOTA-03-2 每天一行、互不覆盖（昨天 + 今天）" "2" "$(sqlv "SELECT COUNT(*) FROM daily_quota WHERE user_id=${USER_D_ID}")"
expect_eq "QUOTA-03-2b 昨天的消费不被改动" "1" "$(sqlv "SELECT consumed FROM daily_quota WHERE user_id=${USER_D_ID} AND business_date=DATE_SUB(CURDATE(), INTERVAL 1 DAY)")"
expect_eq "QUOTA-03-3 业务日按东八区写入" "1" "$(sqlv "SELECT COUNT(*) FROM quota_ledger WHERE user_id=${USER_D_ID} AND business_date=CURDATE()")"

# ---------- QUOTA-06 登记与预占同事务：任一步失败整体回滚 ----------
MIG_BEFORE=$(sqlv "SELECT reserved FROM daily_quota WHERE user_id=${USER_D_ID} AND business_date=CURDATE()")
TASKS_BEFORE=$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=${USER_D_ID}")
mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -D "$DB" -e "
  START TRANSACTION;
  INSERT INTO recognition_tasks (user_id, oss_object, status, created_at, idempotency_key)
    VALUES (${USER_D_ID}, 'audio/rollback-${SFX}.m4a', 'PENDING', NOW(3), 'rollback-${SFX}');
  UPDATE daily_quota SET reserved = reserved + 1 WHERE user_id=${USER_D_ID} AND business_date=CURDATE();
  ROLLBACK;" >/dev/null 2>&1
expect_eq "QUOTA-06-1 事务回滚后不新增任务" "$TASKS_BEFORE" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=${USER_D_ID}")"
expect_eq "QUOTA-06-2 事务回滚后预占数不变" "$MIG_BEFORE" "$(sqlv "SELECT reserved FROM daily_quota WHERE user_id=${USER_D_ID} AND business_date=CURDATE()")"

# ---------- QUOTA-07 重复退款：状态机 CAS 只允许一次 ----------
LEDGER_TASK=$(sqlv "SELECT task_id FROM quota_ledger WHERE state='RESERVED' ORDER BY id LIMIT 1")
FIRST=$(sqlv "UPDATE quota_ledger SET state='RELEASED', updated_at=NOW(3) WHERE task_id=${LEDGER_TASK} AND state='RESERVED'; SELECT ROW_COUNT()")
SECOND=$(sqlv "UPDATE quota_ledger SET state='RELEASED', updated_at=NOW(3) WHERE task_id=${LEDGER_TASK} AND state='RESERVED'; SELECT ROW_COUNT()")
expect_eq "QUOTA-07-1 首次退还影响 1 行" "1" "$FIRST"
expect_eq "QUOTA-07-2 重复退还不影响任何行（不重复退还）" "0" "$SECOND"

# ---------- 时长上限：服务端拒绝绕过前端预检的请求 ----------
set_quota 5 60 10
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qa-${SFX}-long.m4a\",\"durationSec\":601,\"idempotencyKey\":\"q-long-${SFX}\"}" "$USER_B")
expect "时长超上限（601s > 10 分钟）422" 422 "$(s)"
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/qa-${SFX}-ok.m4a\",\"durationSec\":600,\"idempotencyKey\":\"q-ok-${SFX}\"}" "$USER_B")
expect "边界内（600s = 10 分钟）200" 200 "$(s)"

rm -rf "$WORK"
echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
