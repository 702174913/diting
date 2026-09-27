#!/usr/bin/env bash
# =====================================================================
# S10 后台管理页与审计：三角色权限矩阵、脱敏、原件签发留痕、日志无敏感内容
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + test profile 实例（桩听悟 + 内存存储）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8089 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s10_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>'
#
# 运行：MYSQL_PWD='<库密码>' S10_ADMIN_PASSWORD='<密码>' bash run_s10_admin_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S10_BASE_URL:-http://127.0.0.1:8089}
DB=${S10_TEST_DB:-diting_s10_test}
ADMIN_USER=${S10_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S10_ADMIN_PASSWORD:-}" ]; then echo "缺少 S10_ADMIN_PASSWORD" >&2; exit 2; fi
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
PHONE='13812345678'
IDCARD='11010119900307721X'

echo "== S10 后台与审计端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：三个后台角色 ----------
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S10_ADMIN_PASSWORD}\"}")
ADMIN_TOKEN=$(p | jq -r '.data.token')
call POST /api/admin/accounts "{\"username\":\"op_${SFX}\",\"password\":\"Operator#2026x\",\"nickname\":\"运营\",\"role\":\"OPERATOR\"}" "$ADMIN_TOKEN" >/dev/null
call POST /api/admin/accounts "{\"username\":\"ir_${SFX}\",\"password\":\"Investor#2026x\",\"nickname\":\"投资人\",\"role\":\"INVESTOR\"}" "$ADMIN_TOKEN" >/dev/null
RESP=$(call POST /api/admin/login "{\"username\":\"op_${SFX}\",\"password\":\"Operator#2026x\"}")
OPERATOR_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/admin/login "{\"username\":\"ir_${SFX}\",\"password\":\"Investor#2026x\"}")
INVESTOR_TOKEN=$(p | jq -r '.data.token')
expect_eq "三个后台角色就绪（ADMIN 另计）" "2" "$(sqlv "SELECT COUNT(*) FROM admin_users WHERE username IN ('op_${SFX}','ir_${SFX}')" )"
RESP=$(call POST /api/auth/register "{\"username\":\"u_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_TOKEN=$(p | jq -r '.data.token')
USER_ID=$(p | jq -r '.data.userInfo.id')

# ---------- 造一条含敏感信息的记录（走完整上传链路 + 直接补句子） ----------
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$USER_TOKEN")
UPLOAD_ID=$(p | jq -r '.data.uploadId')
call POST /api/test/object-storage/put "{\"uploadId\":\"${UPLOAD_ID}\",\"contentBase64\":\"${M4A_B64}\"}" "$USER_TOKEN" >/dev/null
call POST /api/upload/complete "{\"uploadId\":${UPLOAD_ID}}" "$USER_TOKEN" >/dev/null
RESP=$(call POST /api/task/submit "{\"uploadId\":${UPLOAD_ID},\"declaredDurationSec\":60}" "$USER_TOKEN")
TASK_ID=$(p | jq -r '.data.id')
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES
  (${TASK_ID}, 1, 0, 0, 3000, '你好，我的手机号是 ${PHONE}，身份证 ${IDCARD}。', 0),
  (${TASK_ID}, 2, 1, 3000, 6000, '知道了。', 0)" >/dev/null
sqlv "UPDATE recognition_tasks SET status='ANALYZING', expires_at=DATE_ADD(NOW(3), INTERVAL 30 DAY) WHERE id=${TASK_ID}" >/dev/null
call POST /api/admin/rules "{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"索取隐私\",\"description\":\"平台规则\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"手机号\"],\"deduct\":30}" "$ADMIN_TOKEN" >/dev/null
call POST "/api/report/${TASK_ID}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"AGENT","1":"CUSTOMER"}}' "$USER_TOKEN" >/dev/null
expect_eq "报告已发布（供后台查看）" "PUBLISHED" "$(sqlv "SELECT status FROM report_versions WHERE task_id=${TASK_ID} AND version=1")"

# ---------- ADMIN-01 三角色矩阵：录音资产 ----------
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20&userId=${USER_ID}" '' "$ADMIN_TOKEN")
expect "ADMIN 读录音资产 200" 200 "$(s)"
expect_jq "分页信封一致" '.data | has("items") and has("total") and has("page")'
expect_jq "仅返回该用户的记录" '.data.items[0].userId == '"${USER_ID}"''
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20" '' "$OPERATOR_TOKEN")
expect "OPERATOR 读录音资产 200" 200 "$(s)"
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20" '' "$INVESTOR_TOKEN")
expect "ADMIN-01-1 INVESTOR 无个体数据 403" 403 "$(s)"
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20" '' "$USER_TOKEN")
expect "ADMIN-02-1 用户 token 403" 403 "$(s)"

# ---------- 详情：ADMIN 原文 / OPERATOR 脱敏 ----------
RESP=$(call GET "/api/admin/recordings/${TASK_ID}" '' "$ADMIN_TOKEN")
expect "ADMIN 读详情 200" 200 "$(s)"
expect_jq "ADMIN 看到原文且未脱敏" '.data.redacted == false and (.data.sentences[0].text | test("13812345678"))'
RESP=$(call GET "/api/admin/recordings/${TASK_ID}" '' "$OPERATOR_TOKEN")
expect "OPERATOR 读详情 200" 200 "$(s)"
expect_jq "OPERATOR 看到的是脱敏文本" '.data.redacted == true and (.data.sentences[0].text | test("\\*"))'
expect_jq "脱敏后仍带高亮区间（等长掩码）" '(.data.sentences[0].highlights | length) >= 1'
if grep -q "$PHONE" <<<"$(p)"; then bad "OPERATOR 响应不含手机号原文" "$(p)"; else ok "OPERATOR 响应不含手机号原文"; fi
if grep -q "$IDCARD" <<<"$(p)"; then bad "OPERATOR 响应不含身份证原文" "$(p)"; else ok "OPERATOR 响应不含身份证原文"; fi
RESP=$(call GET "/api/admin/recordings/${TASK_ID}" '' "$INVESTOR_TOKEN")
expect "ADMIN-01-2 INVESTOR 读详情 403" 403 "$(s)"

# ---------- ADMIN-06 原件签发：仅 ADMIN，且留痕 ----------
RESP=$(call GET "/api/admin/recordings/${TASK_ID}/audio" '' "$ADMIN_TOKEN")
expect "ADMIN 签发原件 200" 200 "$(s)"
expect_jq "返回短时链接与期限" '.data.url != null and .data.expiresAt != null'
RESP=$(call GET "/api/admin/recordings/${TASK_ID}/audio" '' "$OPERATOR_TOKEN")
expect "ADMIN-01-3 OPERATOR 拿不到原件 403" 403 "$(s)"
RESP=$(call GET "/api/admin/recordings/${TASK_ID}/audio" '' "$INVESTOR_TOKEN")
expect "ADMIN-01-4 INVESTOR 403" 403 "$(s)"
expect_eq "ADMIN-06-1 签发动作已留痕" "1" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='AUDIO_PRESIGN' AND target_id='${TASK_ID}'")"

# ---------- 角色确认与删除的权限 ----------
RESP=$(call POST "/api/admin/recordings/${TASK_ID}/speaker-map" '{"expectedRevision":1,"mapping":{"0":"AGENT","1":"CUSTOMER"}}' "$OPERATOR_TOKEN")
expect "OPERATOR 可确认角色 200" 200 "$(s)"
RESP=$(call POST "/api/admin/recordings/${TASK_ID}/speaker-map" '{"expectedRevision":2,"mapping":{"0":"AGENT","1":"CUSTOMER"}}' "$INVESTOR_TOKEN")
expect "ADMIN-01-5 INVESTOR 不能确认角色 403" 403 "$(s)"
RESP=$(call DELETE "/api/admin/recordings/${TASK_ID}" '' "$OPERATOR_TOKEN")
expect "ADMIN-01-6 OPERATOR 不能删除 403" 403 "$(s)"

# ---------- 审计日志：仅 ADMIN，且不含对话原文与链接 ----------
RESP=$(call GET "/api/admin/audit-logs?page=1&pageSize=50" '' "$ADMIN_TOKEN")
expect "ADMIN 读审计 200" 200 "$(s)"
expect_jq "审计含动作与目标元数据" '(.data.items | length) >= 1 and (.data.items[0] | has("action") and has("targetType"))'
RESP=$(call GET "/api/admin/audit-logs?page=1&pageSize=50" '' "$OPERATOR_TOKEN")
expect "ADMIN-01-7 OPERATOR 读审计 403" 403 "$(s)"
expect_eq "ADMIN-07-1 审计未记录对话原文" "0" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%手机号%' OR detail LIKE '%${PHONE}%'")"
expect_eq "ADMIN-07-2 审计未记录签名链接" "0" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%http%'")"
expect_eq "ADMIN-07-3 审计未记录密码字段" "0" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%password%' OR detail LIKE '%2a%'")"
RESP=$(call POST /api/admin/config "{\"expectedVersion\":1,\"dailyFreeQuota\":3,\"riskThreshold\":60,\"maxRecordingMinutes\":10}" "$ADMIN_TOKEN")
expect "ADMIN 保存配置 200" 200 "$(s)"
expect_eq "配置与规则变更都已留痕" "2" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action IN ('RULE_CREATE','CONFIG_SAVE')")"

# ---------- ADMIN-08 禁用与角色变更即时生效 ----------
OP_ID=$(sqlv "SELECT id FROM admin_users WHERE username='op_${SFX}'")
RESP=$(call PUT "/api/admin/accounts/${OP_ID}" '{"enabled":false}' "$ADMIN_TOKEN")
expect "禁用 OPERATOR 200" 200 "$(s)"
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20" '' "$OPERATOR_TOKEN")
expect "ADMIN-08-1 禁用后旧 token 立即失效 403" 403 "$(s)"
RESP=$(call PUT "/api/admin/accounts/${OP_ID}" '{"role":"INVESTOR","enabled":true}' "$ADMIN_TOKEN")
expect "把 OPERATOR 改为 INVESTOR 200" 200 "$(s)"
RESP=$(call POST /api/admin/login "{\"username\":\"op_${SFX}\",\"password\":\"Operator#2026x\"}")
NOROLE_TOKEN=$(p | jq -r '.data.token')
RESP=$(call GET "/api/admin/recordings?page=1&pageSize=20" '' "$NOROLE_TOKEN")
expect "ADMIN-08-2 角色变更后立即无个体数据权限 403" 403 "$(s)"
expect_eq "账号变更逐次留痕（禁用 + 改角色）" "2" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='ACCOUNT_UPDATE' AND target_id='${OP_ID}'")"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
