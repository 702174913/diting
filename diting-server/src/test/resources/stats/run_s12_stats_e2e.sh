#!/usr/bin/env bash
# =====================================================================
# 总览统计端到端验证（STAT-01/02/04/06/07）
# 统计面收敛后只保留 /api/admin/stats/overview；其余分组（growth/finance/ops/
# violations/quality/compliance）与 /api/admin/ops/queue 已下线，本脚本对它们断言 404。
#
# 需要：已迁移的独立测试库 + test profile 实例（8901）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8901 \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s12_test?...' \
#     --diting.jwt.secret='<测试密钥>'
# 运行：MYSQL_PWD='<库密码>' S12_ADMIN_PASSWORD='<密码>' bash run_s12_stats_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S12_BASE_URL:-http://127.0.0.1:8901}
DB=${S12_TEST_DB:-diting_s12_test}
ADMIN_USER=${S12_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S12_ADMIN_PASSWORD:-}" ]; then echo "缺少 S12_ADMIN_PASSWORD" >&2; exit 2; fi
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾" >&2; exit 2 ;; esac

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM
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
metric(){ jq -r ".data.metrics[] | select(.key == \"$1\") | $2" <<<"$(p)"; }
breakdown_row(){ jq -r ".data.breakdown[] | select(.key == \"$1\") | .rows[] | select(.key == \"$2\") | $3" <<<"$(p)"; }

echo "== 总览统计端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S12_ADMIN_PASSWORD}\"}")
expect "后台登录 200" 200 "$(s)"
ADMIN_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/admin/accounts "{\"username\":\"ir_${SFX}\",\"password\":\"Investor#2026x\",\"nickname\":\"投资人\",\"role\":\"INVESTOR\"}" "$ADMIN_TOKEN")
RESP=$(call POST /api/admin/login "{\"username\":\"ir_${SFX}\",\"password\":\"Investor#2026x\"}")
IR_TOKEN=$(p | jq -r '.data.token')

# ---------- 固定数据集：5 个任务（2 完成 / 1 失败 / 1 无效 / 1 排队）、2 份有效报告、1 个授权会话、1 次角色确认 ----------
# 先清掉上一轮遗留的统计夹具，保证 COUNT 断言可复现（只删本脚本造的数据）
sqlv "DELETE FROM report_versions WHERE task_id IN (SELECT id FROM recognition_tasks WHERE user_id = 9001)" >/dev/null
sqlv "DELETE FROM upload_sessions WHERE user_id = 9001" >/dev/null
sqlv "DELETE FROM audit_log WHERE target_type = 'TASK' AND target_id IN (SELECT CAST(id AS CHAR) FROM recognition_tasks WHERE user_id = 9001)" >/dev/null
sqlv "DELETE FROM recognition_tasks WHERE user_id = 9001" >/dev/null
sqlv "DELETE FROM users WHERE id = 9001" >/dev/null
sqlv "INSERT INTO users (id, username, nickname, created_at) VALUES (9001, 'stat_${SFX}', '统计样本', NOW(3))" >/dev/null
sqlv "INSERT INTO upload_sessions (user_id, object_key, status, filename, consent_version, consented_at, signed_until, cleanup_after, created_at) VALUES
  (9001, 'audio/s0-${SFX}.m4a', 'CONSUMED', 'sample.m4a', 'v1', NOW(3), DATE_ADD(NOW(3), INTERVAL 1 HOUR), DATE_ADD(NOW(3), INTERVAL 1 DAY), NOW(3))" >/dev/null
sqlv "INSERT INTO recognition_tasks (user_id, oss_object, status, duration_sec, created_at, finished_at, expires_at) VALUES
  (9001, 'audio/s1-${SFX}.m4a', 'COMPLETED', 100, DATE_SUB(NOW(3), INTERVAL 2 DAY), DATE_SUB(NOW(3), INTERVAL 2 DAY), DATE_ADD(NOW(3), INTERVAL 30 DAY)),
  (9001, 'audio/s2-${SFX}.m4a', 'COMPLETED', 200, DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 30 DAY)),
  (9001, 'audio/s3-${SFX}.m4a', 'FAILED',    300, DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 30 DAY)),
  (9001, 'audio/s4-${SFX}.m4a', 'INVALID',    10, NOW(3), NOW(3), DATE_ADD(NOW(3), INTERVAL 30 DAY)),
  (9001, 'audio/s5-${SFX}.m4a', 'QUEUED',   NULL, NOW(3), NULL, NULL)" >/dev/null
T1=$(sqlv "SELECT id FROM recognition_tasks WHERE oss_object='audio/s1-${SFX}.m4a'")
T2=$(sqlv "SELECT id FROM recognition_tasks WHERE oss_object='audio/s2-${SFX}.m4a'")
sqlv "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, risk_score, compliance_score, threshold_snapshot, config_version, status, published_at, created_at) VALUES
  (${T1}, 1, 1, 1, 70, 30, 60, 1, 'PUBLISHED', DATE_SUB(NOW(3), INTERVAL 2 DAY), DATE_SUB(NOW(3), INTERVAL 2 DAY)),
  (${T1}, 2, 1, 2, 40, 60, 60, 1, 'PUBLISHED', DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_SUB(NOW(3), INTERVAL 1 DAY)),
  (${T2}, 1, 1, 1, 10, 90, 60, 1, 'PUBLISHED', NOW(3), NOW(3)),
  (${T2}, 2, 1, 2, NULL, NULL, 60, 1, 'FAILED', NULL, NOW(3))" >/dev/null
# 角色确认留痕：覆盖率的分母是完成量，分子是「发生过 SPEAKER_MAP_CONFIRM 的任务数」
sqlv "INSERT INTO audit_log (actor_domain, actor_id, action, target_type, target_id, result, created_at) VALUES
  ('USER', 9001, 'SPEAKER_MAP_CONFIRM', 'TASK', '${T1}', 'SUCCESS', NOW(3))" >/dev/null

# ---------- STAT-01 固定数据 COUNT 核对 ----------
RESP=$(call GET '/api/admin/stats/overview?period=30d' '' "$ADMIN_TOKEN")
expect "聚合接口 200" 200 "$(s)"
expect_eq "STAT-01-1 提交任务量（本样本 5 个）" "5" "$(metric submittedTasks '.value')"
expect_eq "STAT-01-2 完成量（2 个）" "2" "$(metric completedTasks '.value')"
expect_eq "STAT-01-3 失败量只算 FAILED（1 个）" "1" "$(metric failedTasks '.value')"
expect_eq "STAT-01-4 无有效语音单列（1 个）" "1" "$(metric invalidTasks '.value')"
expect_eq "STAT-01-5 失败率分母=完成+失败+无效（1/4=25.0%）" "25.0" "$(metric failureRate '.value')"
expect_eq "STAT-01-6 失败率分母可追溯" "4" "$(metric failureRate '.denominator')"
expect_eq "STAT-01-7 当前队列是时点库存（排队 1）" "1" "$(metric queueNow '.value')"
expect_eq "STAT-01-8 平均耗时有样本量（2 个完成样本）" "2" "$(metric avgDuration '.sampleSize')"
expect_eq "STAT-04-1 有效报告量按 task 去重（T1 两版只算 1）" "2" "$(metric publishedReports '.value')"
TOTAL_METRICS=$(p | jq -r '.data.metrics | length')
WITH_SOURCE=$(p | jq -r '[.data.metrics[] | select(.sourceTable != null)] | length')
expect_eq "STAT-01-9 每个指标都带来源表（${TOTAL_METRICS} 项）" "$TOTAL_METRICS" "$WITH_SOURCE"

# ---------- 漏斗与序列：全部用户口径，逐环是上一步子集 ----------
expect_eq "STAT-01-10 上传率分母=区间新增注册（1 人）" "1" "$(metric uploadRate '.denominator')"
expect_eq "STAT-01-11 上传率按真实样本计算（1/1=100.0%）" "100.0" "$(metric uploadRate '.value')"
expect_eq "STAT-01-12 角色确认覆盖率分母=完成量（2）" "50.0" "$(metric confirmCoverage '.value')"
expect_eq "STAT-01-13 漏斗六环且按用户去重" "6" \
  "$(p | jq -r '.data.breakdown[] | select(.key == "userFunnel") | .rows | length')"
expect_eq "STAT-01-14 漏斗首环=区间新增注册" "1" "$(breakdown_row userFunnel registered '.value')"
expect_eq "STAT-01-15 按日序列补零到整窗口（30 天）" "30" \
  "$(p | jq -r '.data.series[] | select(.key == "dailyReports") | .points | length')"
expect_eq "STAT-01-16 序列与指标同口径（有报告的那天不是 0）" "1" \
  "$(p | jq -r '[.data.series[] | select(.key == "dailyReports") | .points[] | select(.value > 0)] | length > 0 | if . then 1 else 0 end')"

# ---------- STAT-02 零分母返回 null ----------
RESP=$(call GET '/api/admin/stats/overview?from=2020-01-01&to=2020-01-02' '' "$ADMIN_TOKEN")
expect "空区间聚合 200" 200 "$(s)"
expect_eq "STAT-02-1 空区间失败率为 null" "null" "$(metric failureRate '.value')"
expect_eq "STAT-02-2 空区间失败率分母为 null" "null" "$(metric failureRate '.denominator')"
expect_eq "STAT-02-3 空区间提交量为 0" "0" "$(metric submittedTasks '.value')"

# ---------- STAT-06 相同筛选前后端一致 ----------
RESP=$(call GET '/api/admin/stats/overview?from='"$(date -v-30d +%Y-%m-%d)"'&to='"$(date +%Y-%m-%d)" '' "$ADMIN_TOKEN")
expect_eq "STAT-06-1 显式区间与 period 口径一致" "5" "$(metric submittedTasks '.value')"
expect_eq "STAT-06-2 区间回显给前端" "$(date +%Y-%m-%d)" "$(p | jq -r '.data.to')"

# ---------- STAT-07 投资人可读聚合、无个体字段 ----------
RESP=$(call GET '/api/admin/stats/overview?period=30d' '' "$IR_TOKEN")
expect "STAT-07-1 投资人可读聚合 200" 200 "$(s)"
if grep -qE '"(userId|taskId|sentenceId|reportVersionId)"' <<<"$(p)"; then
  bad "STAT-07-2 聚合响应不含个体标识" "$(p)"
else
  ok "STAT-07-2 聚合响应不含个体标识"
fi
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=10" '' "$IR_TOKEN")
expect "STAT-07-3 投资人仍拿不到个体明细 403" 403 "$(s)"

# ---------- 统计面收敛：已下线的分组与接口必须 404，而不是继续吐数据 ----------
for section in growth finance ops violations quality compliance; do
  RESP=$(call GET "/api/admin/stats/${section}?period=30d" '' "$ADMIN_TOKEN")
  expect "下线分组 ${section} 404" 404 "$(s)"
done
RESP=$(call GET '/api/admin/stats/unknown-section' '' "$ADMIN_TOKEN")
expect "未知分组 404" 404 "$(s)"
RESP=$(call GET '/api/admin/ops/queue?limit=5' '' "$ADMIN_TOKEN")
expect "下线接口 /api/admin/ops/queue 404" 404 "$(s)"
RESP=$(call GET '/api/admin/growth?period=30d' '' "$ADMIN_TOKEN")
expect "下线占位端点 /api/admin/growth 404" 404 "$(s)"
RESP=$(call GET '/api/admin/finance?period=30d' '' "$ADMIN_TOKEN")
expect "下线占位端点 /api/admin/finance 404" 404 "$(s)"
RESP=$(call GET '/api/admin/dashboard?period=30d' '' "$ADMIN_TOKEN")
expect "旧占位端点 /api/admin/dashboard 仍 200（权限探针）" 200 "$(s)"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
