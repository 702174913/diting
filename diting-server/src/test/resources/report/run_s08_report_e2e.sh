#!/usr/bin/env bash
# =====================================================================
# S08 角色确认与版本化报告端到端验证
#   覆盖：角色映射版本冲突、每次确认生成新报告版本、对调角色结果变化、
#         计数口径（flagged vs violation）、历史版本可读、XSS 文本透传、缺原件仍可读。
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + 以 test profile 启动的实例（桩听悟 + 内存存储）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8087 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s08_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>'
#
# 运行：MYSQL_PWD='<库密码>' S08_ADMIN_PASSWORD='<密码>' bash run_s08_report_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S08_BASE_URL:-http://127.0.0.1:8087}
DB=${S08_TEST_DB:-diting_s08_test}
ADMIN_USER=${S08_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S08_ADMIN_PASSWORD:-}" ]; then echo "缺少 S08_ADMIN_PASSWORD" >&2; exit 2; fi
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
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}（$(p)）"; fi; }

M4A_B64=$(python3 -c "import base64;d=bytearray(64);h=b'0000ftypM4A ';d[0:len(h)]=h;print(base64.b64encode(bytes(d)).decode())")

echo "== S08 报告端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：一个已校验上传 → 已登记任务 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"r_${SFX}\",\"password\":\"Passw0rd2026\"}")
expect "注册 200" 200 "$(s)"
TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$TOKEN")
UPLOAD_ID=$(p | jq -r '.data.uploadId')
call POST /api/test/object-storage/put "{\"uploadId\":\"${UPLOAD_ID}\",\"contentBase64\":\"${M4A_B64}\"}" "$TOKEN" >/dev/null
call POST /api/upload/complete "{\"uploadId\":${UPLOAD_ID}}" "$TOKEN" >/dev/null
RESP=$(call POST /api/task/submit "{\"uploadId\":${UPLOAD_ID},\"declaredDurationSec\":60}" "$TOKEN")
TASK_ID=$(p | jq -r '.data.id')
expect_eq "任务已登记" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${TASK_ID}")"

# 句子按时间序落库（真实链路由 S06 管线写入；这里直接造数据以便聚焦报告链路）
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES
  (${TASK_ID}, 1, 0, 0, 3000, '你好，我是某某公司的客服。', 0),
  (${TASK_ID}, 2, 1, 3000, 6000, '我知道了，<script>alert(1)</script>保证免息。', 0),
  (${TASK_ID}, 3, 0, 6000, 9000, '请你今天处理。', 0)" >/dev/null
sqlv "UPDATE recognition_tasks SET status='ANALYZING', duration_sec=9, expires_at=DATE_ADD(NOW(3), INTERVAL 30 DAY) WHERE id=${TASK_ID}" >/dev/null

# 两条规则：AGENT 命中「保证免息」扣 30；CUSTOMER 必须出现「我知道」扣 20
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S08_ADMIN_PASSWORD}\"}")
ADMIN_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/admin/rules "{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"禁用承诺\",\"description\":\"平台规则第 3 条\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"保证免息\"],\"deduct\":30}" "$ADMIN_TOKEN")
RULE_OK=$(s)
RESP=$(call POST /api/admin/rules "{\"code\":\"R-MUST-${SFX}\",\"groupName\":\"身份\",\"name\":\"客户确认\",\"description\":\"平台规则第 1 条\",\"matchType\":\"must-have\",\"roleScope\":\"CUSTOMER\",\"keywords\":[\"我不同意\"],\"deduct\":20}" "$ADMIN_TOKEN")
expect_eq "规则准备完成" "200" "$RULE_OK"

# ---------- REPORT-07 未确认角色：先能读到句子，角色为空 ----------
RESP=$(call GET "/api/report/${TASK_ID}" '' "$TOKEN")
expect "读取报告 200" 200 "$(s)"
expect_jq "未确认角色时 rolesConfirmed=false" '.data.rolesConfirmed == false'
expect_jq "句子按时间序返回且带原文" '(.data.sentences | length) == 3 and .data.sentences[0].text == "你好，我是某某公司的客服。"'
expect_jq "未确认角色时给出说明" '.data.notes | join(" ") | test("角色尚未确认")'

# ---------- 角色确认 → 发布第一个报告版本 ----------
RESP=$(call POST "/api/report/${TASK_ID}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"AGENT","1":"CUSTOMER"}}' "$TOKEN")
expect "确认角色 200" 200 "$(s)"
expect_jq "返回映射版本 1 与已发布状态" '.data.mappingRevision == 1 and .data.analysisStatus == "PUBLISHED"'
expect_eq "报告版本 1 已发布" "PUBLISHED" "$(sqlv "SELECT status FROM report_versions WHERE task_id=${TASK_ID} AND version=1")"
expect_eq "规则集合版本已记录" "1" "$(sqlv "SELECT COUNT(*) FROM report_versions WHERE task_id=${TASK_ID} AND version=1 AND rule_set_version >= 1 AND threshold_snapshot=60")"
expect_eq "任务在报告发布后置为完成" "COMPLETED" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${TASK_ID}")"
V1_RISK=$(sqlv "SELECT risk_score FROM report_versions WHERE task_id=${TASK_ID} AND version=1")

# ---------- REPORT-02 并发确认冲突 ----------
RESP=$(call POST "/api/report/${TASK_ID}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"CUSTOMER","1":"AGENT"}}' "$TOKEN")
expect "过期映射版本 409" 409 "$(s)"

# ---------- REPORT-01 对调角色产生可解释变化 ----------
RESP=$(call POST "/api/report/${TASK_ID}/speaker-map" '{"expectedRevision":1,"mapping":{"0":"CUSTOMER","1":"AGENT"}}' "$TOKEN")
expect "对调角色 200" 200 "$(s)"
expect_jq "生成新报告版本 2" '.data.reportVersion == 2'
V2_RISK=$(sqlv "SELECT risk_score FROM report_versions WHERE task_id=${TASK_ID} AND version=2")
if [ "$V1_RISK" != "$V2_RISK" ]; then
  ok "对调角色后风险分变化（v1=${V1_RISK} → v2=${V2_RISK}）"
else
  bad "对调角色后风险分应变化" "两版都是 ${V1_RISK}"
fi
expect_eq "旧版本仍可读" "$V1_RISK" "$(sqlv "SELECT risk_score FROM report_versions WHERE task_id=${TASK_ID} AND version=1")"

# ---------- REPORT-06/09 计数与高亮 ----------
RESP=$(call GET "/api/report/${TASK_ID}" '' "$TOKEN")
expect_jq "命中句数与违规数分别给出" '(.data.flaggedCount >= 0) and (.data.violationCount >= .data.flaggedCount)'
expect_jq "分数与判读结果一致（compliance=100-risk）" '.data.complianceScore == (100 - .data.riskScore)'
expect_jq "风险标记与阈值口径一致" 'if .data.riskScore >= .data.threshold then .data.riskFlagged == true else .data.riskFlagged == false end'
expect_jq "命中证据带原文高亮区间" '([.data.violations[] | select(.ranges | length > 0)] | length) >= 1'
expect_jq "通话级缺失项的 sentenceId 为空" '([.data.violations[] | select(.sentenceId == null)] | length) >= 1'
expect_jq "命中句附带规则名与说明" '([.data.sentences[] | select((.notes | length) > 0)] | length) >= 1'

# ---------- REPORT-05 XSS 文本原样返回 ----------
if grep -q "<script>alert(1)</script>" <<<"$(p)"; then
  ok "REPORT-05 文本原样返回（服务端不生成 HTML）"
else
  bad "REPORT-05 文本应原样返回" "未在响应中找到原始文本"
fi

# ---------- 历史版本读取 ----------
RESP=$(call GET "/api/report/${TASK_ID}?version=1" '' "$TOKEN")
expect_jq "按版本读取返回 v1" '.data.reportVersion == 1'
expect_jq "v1 分数与库里一致" ".data.riskScore == ${V1_RISK}"
RESP=$(call GET "/api/report/${TASK_ID}?version=99" '' "$TOKEN")
expect "不存在的版本 404" 404 "$(s)"

# ---------- REPORT-03 重算失败：旧版本仍可读并带状态说明 ----------
sqlv "INSERT INTO report_versions (task_id, version, rule_set_version, mapping_revision, threshold_snapshot, config_version, status, created_at) VALUES (${TASK_ID}, 3, 1, 2, 60, 1, 'FAILED', NOW(3))" >/dev/null
RESP=$(call GET "/api/report/${TASK_ID}" '' "$TOKEN")
expect_jq "展示最近成功发布的版本" '.data.status == "PUBLISHED" and .data.reportVersion == 2'
expect_jq "提示最新一次重算未完成" '.data.latestStatus == "FAILED" and (.data.notes | join(" ") | test("重算未完成"))'

# ---------- REPORT-08 原件清理后文本仍可读 ----------
sqlv "UPDATE recognition_tasks SET audio_purged=1 WHERE id=${TASK_ID}" >/dev/null
RESP=$(call GET "/api/report/${TASK_ID}" '' "$TOKEN")
expect_jq "音频不可用但文本可读" '.data.audioAvailable == false and (.data.sentences | length) == 3'
expect_jq "给出原件状态说明" '.data.notes | join(" ") | test("原始录音")'

# ---------- 归属：他人读不到 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"r2_${SFX}\",\"password\":\"Passw0rd2026\"}")
OTHER_TOKEN=$(p | jq -r '.data.token')
RESP=$(call GET "/api/report/${TASK_ID}" '' "$OTHER_TOKEN")
expect "他人读取报告 404" 404 "$(s)"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
